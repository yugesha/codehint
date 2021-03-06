package codehint.effects;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRunnable;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IBreakpoint;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.debug.core.IJavaArray;
import org.eclipse.jdt.debug.core.IJavaClassPrepareBreakpoint;
import org.eclipse.jdt.debug.core.IJavaClassType;
import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaFieldVariable;
import org.eclipse.jdt.debug.core.IJavaMethodEntryBreakpoint;
import org.eclipse.jdt.debug.core.IJavaObject;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.jdt.internal.debug.core.breakpoints.JavaClassPrepareBreakpoint;
import org.eclipse.jdt.internal.debug.core.breakpoints.JavaMethodEntryBreakpoint;
import org.eclipse.jdt.internal.debug.core.breakpoints.JavaWatchpoint;
import org.eclipse.jdt.internal.debug.core.model.JDIDebugTarget;
import org.eclipse.jdt.internal.debug.core.model.JDIFieldVariable;
import org.eclipse.jdt.internal.debug.core.model.JDIObjectValue;
import org.eclipse.jdt.internal.debug.core.model.JDIValue;
import org.eclipse.jdt.internal.debug.ui.BreakpointUtils;

import codehint.utils.EclipseUtils;
import codehint.utils.MutablePair;
import codehint.utils.Pair;
import codehint.utils.Utils;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ClassNotLoadedException;
import com.sun.jdi.ClassObjectReference;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.InvalidTypeException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.event.AccessWatchpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.ModificationWatchpointEvent;
import com.sun.jdi.event.WatchpointEvent;

public class SideEffectHandler {
	
	private final IJavaStackFrame stack;
	private final IJavaProject project;
	private long maxID;
	private final Set<String> neverModifiedFields;
	
	@SuppressWarnings("unchecked")
	public SideEffectHandler(IJavaStackFrame stack, IJavaProject project) {
		this.stack = stack;
		this.project = project;
		try {
			ObjectInputStream is = new ObjectInputStream(new GZIPInputStream(EclipseUtils.getFileFromBundle("data" + System.getProperty("file.separator") + "notmod.gz")));
			neverModifiedFields = (Set<String>)is.readObject();
			is.close();
		} catch (IOException e) {
			throw new RuntimeException(e);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}
	
	// TODO: I could easily remove the requirement that we can get instance info.
	public boolean canHandleSideEffects() {
		IJavaDebugTarget target = (IJavaDebugTarget)stack.getDebugTarget();
		return target.supportsModificationWatchpoints() && target.supportsAccessWatchpoints() && target.supportsInstanceRetrieval();
	}
	
	// TODO: Call this in the constructor to reduce the slowness?  If so, change Synthesizer so it doesn't disable or double-delete these breakpoints.  Also ensure we always delete these breakpoints.
	public void start(IProgressMonitor monitor) {
		if (!enabled)
			return;
		SubMonitor curMonitor = SubMonitor.convert(monitor, "Side effect handler setup", IProgressMonitor.UNKNOWN);
		Map<String, ArrayList<IJavaObject>> instancesCache = new HashMap<String, ArrayList<IJavaObject>>();
		Collection<IField> fields = getFields(instancesCache, curMonitor);
		curMonitor.setWorkRemaining(fields.size());
		addedWatchpoints = getFieldWatchpoints(fields, instancesCache);
		curMonitor.worked(fields.size());
		try {
			prepareBreakpoint = new MyClassPrepareBreakpoint(project);
		} catch (DebugException e) {
			throw new RuntimeException(e);
		}
		reflectionBreakpoints = getReflectionBreakpoints(project);
		if (collectionDisableds == null)
			collectionDisableds = new ArrayList<ObjectReference>();
		curMonitor.done();
	}

	private static List<ReferenceType> getAllLoadedTypes(IJavaStackFrame stack) {
		//long startTime = System.currentTimeMillis();
		List<ReferenceType> internalTypes = ((JDIDebugTarget)stack.getDebugTarget()).getVM().allClasses();
		List<ReferenceType> types = new ArrayList<ReferenceType>(internalTypes.size());
		for (ReferenceType type: internalTypes) {
			if (!type.name().contains("[]"))
				types.add(type);
		}
		//System.out.println("types: " + (System.currentTimeMillis() - startTime));
		return types;
	}
	
	private static boolean isUsefulType(String typeName) {
		if ((typeName.startsWith("codehint.") && typeName.indexOf('.', 9) == -1)
				|| typeName.equals("java.lang.String")  // A String's value array will never change and we don't care about its hashCode.
				|| typeName.equals("java.lang.Class")
				|| typeName.equals("java.lang.CharacterDataLatin1")
				|| typeName.equals("java.lang.Integer$IntegerCache")
				|| typeName.equals("java.lang.Integer")
				|| typeName.equals("sun.nio.cs.StandardCharsets")
				|| typeName.equals("sun.awt.SunHints")
				|| typeName.equals("java.awt.RenderingHints")
				|| typeName.startsWith("sun.") || typeName.startsWith("java.security.") || typeName.startsWith("java.nio.") || typeName.startsWith("java.lang.invoke.") || typeName.startsWith("java.lang.ClassLoader") || typeName.equals("java.lang.System") || typeName.equals("java.lang.Shutdown")
				|| typeName.equals("java.lang.SecurityManager") || typeName.startsWith("java.util.Collections$Unmodifiable") || typeName.equals("java.awt.color.ColorSpace") || typeName.equals("java.lang.Math") || typeName.equals("java.util.jar.JarFile") || typeName.startsWith("jdk.internal.org.objectweb.asm"))
			return false;
		if (typeName.equals("java.util.concurrent.locks.AbstractQueuedSynchronizer"))
			return false;  // Heuristically avoiding undoing side effects on sync variables, which can cause hangs in Swing.
		return true;
	}
	
	private boolean isUsefulField(String typeName, String fieldName, String objTypeName) {
		if (neverModifiedFields.contains(typeName.replace("$", ".") + "." + fieldName))
			return false;
		if ((typeName.equals("javax.swing.UIDefaults") && fieldName.equals("PENDING"))
				|| (typeName.equals("javax.swing.UIManager") && fieldName.equals("classLock"))
				|| (typeName.equals("java.awt.Component") && fieldName.equals("LOCK"))
				|| (typeName.equals("sun.util.logging.PlatformLogger$Level") && fieldName.equals("levelValues")))
			return false;
		if (typeName.equals("javax.swing.MultiUIDefaults") && fieldName.equals("tables"))
			return false;
		if (typeName.equals("java.awt.image.DirectColorModel")
				|| ((typeName.startsWith("java.awt.") || typeName.startsWith("javax.swing.")) && fieldName.equals("accessibleContext"))
				|| (typeName.equals("java.lang.ref.SoftReference") && fieldName.equals("timestamp"))
				|| (typeName.equals("java.util.HashSet") && fieldName.equals("PRESENT"))
				|| (typeName.equals("java.lang.StringBuffer") && fieldName.equals("toStringCache"))  // This is just a cache.
				|| (typeName.equals("java.util.AbstractMap") && objTypeName.startsWith("java.util.") && fieldName.equals("keySet"))  // This is a writeonce field that simply creates a wrapper.  But we need to be sure it's not a subclass that has overriden this behavior.
				|| (typeName.equals("java.util.AbstractMap") && objTypeName.startsWith("java.util.") && fieldName.equals("values"))  // This is a writeonce field that simply creates a wrapper.  But we need to be sure it's not a subclass that has overriden this behavior.
				|| (objTypeName.equals("java.util.concurrent.ConcurrentHashMap") && fieldName.equals("keySet"))  // This is a writeonce field that simply creates a wrapper.  But we need to be sure it's not a subclass that has overriden this behavior.
				|| (objTypeName.equals("java.util.concurrent.ConcurrentHashMap") && fieldName.equals("values"))  // This is a writeonce field that simply creates a wrapper.  But we need to be sure it's not a subclass that has overriden this behavior.
				|| (objTypeName.equals("java.util.concurrent.ConcurrentHashMap") && fieldName.equals("entrySet"))  // This is a writeonce field that simply creates a wrapper.  But we need to be sure it's not a subclass that has overriden this behavior.
				|| (typeName.equals("java.lang.Throwable") && fieldName.equals("UNASSIGNED_STACK"))  // This is a constant empty array.
				|| (typeName.equals("java.awt.Container") && fieldName.equals("EMPTY_ARRAY")))  // This is a constant empty array.
			return false;
		return true;
	}
	
	private static boolean canBeArray(IField field) throws JavaModelException {
		String typeSig = field.getTypeSignature();
		return typeSig.contains("[") || typeSig.equals("Ljava.lang.Object;") || typeSig.equals("QObject;");
	}

	private Set<IJavaObject> getAllReachableObjects(SubMonitor monitor) {
		try {
			Set<IJavaObject> objs = new HashSet<IJavaObject>();
			getReachableObjects(stack.getThis(), objs);
			for (IVariable var: stack.getLocalVariables())
				getReachableObjects((IJavaValue)var.getValue(), objs);
			for (ReferenceType type: getAllLoadedTypes(stack)) {
				for (Field field: type.allFields())
					if (field.isStatic() && isUsefulStaticDFSField(type.name(), field))
						getReachableObjects(JDIValue.createValue((JDIDebugTarget)stack.getDebugTarget(), type.getValue(field)), objs);
				monitor.worked(1);
			}
			return objs;
		} catch (DebugException e) {
			throw new RuntimeException(e);
		}
	}
	
	private static boolean isUsefulStaticDFSField(String typeName, Field field) {
		String fieldName = field.name();
		return !((typeName.startsWith("codehint.") && typeName.indexOf('.', 9) == -1)
				|| typeName.equals("java.lang.Integer$IntegerCache")
				|| typeName.equals("java.lang.Short$ShortCache")
				|| typeName.equals("java.lang.Long$LongCache")
				|| typeName.equals("java.lang.Character$CharacterCache")
				|| typeName.equals("java.lang.Byte$ByteCache")
				|| (typeName.equals("sun.misc.VM") && fieldName.equals("savedProps"))
				|| (typeName.equals("java.lang.System") && fieldName.equals("props"))
				|| typeName.equals("java.util.Locale")
				|| typeName.equals("sun.util.locale.BaseLocale")
				|| typeName.equals("java.lang.CharacterDataLatin1")
				|| typeName.equals("java.lang.invoke.LambdaForm$NamedFunction")
				|| typeName.equals("java.lang.invoke.MethodType")
				|| typeName.equals("sun.util.calendar.ZoneInfoFile")
				|| (typeName.equals("java.lang.SecurityManager") && fieldName.equals("packageAccess"))
				|| (typeName.equals("java.lang.SecurityManager") && fieldName.equals("rootGroup"))
				|| (typeName.equals("java.security.Security") && fieldName.equals("props"))
				|| (typeName.equals("sun.security.provider.PolicyFile") && fieldName.equals("policy"))
				|| (typeName.equals("java.security.Policy") && fieldName.equals("policy"))
				|| typeName.equals("sun.launcher.LauncherHelper")
				|| typeName.startsWith("sun.misc.Launcher")
				|| (typeName.startsWith("java.") && typeName.endsWith("ClassLoader") && fieldName.equals("scl"))
				|| (typeName.equals("sun.misc.MetaIndex") && fieldName.equals("jarMap"))
				|| typeName.startsWith("sun.nio.cs.")
				|| (typeName.equals("java.io.File") && fieldName.equals("fs"))
				|| (typeName.equals("java.lang.ClassLoader") && fieldName.equals("classes"))
				|| typeName.equals("java.lang.Class")
				|| typeName.startsWith("sun.") || typeName.startsWith("java.security.") || typeName.startsWith("java.nio.") || typeName.startsWith("java.lang.ref.") || typeName.startsWith("java.lang.invoke.") || typeName.startsWith("java.lang.ClassLoader")
				|| (typeName.equals("java.lang.reflect.Proxy") && fieldName.equals("proxyClassCache"))
				|| ((typeName.equals("javax.swing.KeyStroke") || typeName.equals("java.awt.AWTKeyStroke")) && fieldName.equals("modifierKeywords"))  // Only initialized once.
				|| (typeName.equals("java.awt.VKCollection") && fieldName.equals("code2name"))  // A cache.
				|| (typeName.equals("java.awt.VKCollection") && fieldName.equals("name2code"))  // A cache.
				|| (typeName.equals("java.lang.Package") && fieldName.equals("pkgs"))
				|| (typeName.equals("java.lang.Package") && fieldName.equals("mans"))
				|| (typeName.equals("java.lang.Package") && fieldName.equals("urls"))
				|| (typeName.equals("com.sun.swing.internal.plaf.basic.resources.basic") && fieldName.equals("NONEXISTENT_BUNDLE"))
				|| (typeName.equals("java.lang.ProcessEnvironment") && fieldName.equals("theEnvironment"))  // Never modified.
				|| (typeName.equals("java.lang.ProcessEnvironment") && fieldName.equals("theUnmodifiableEnvironment"))  // Unmodifiable.
				|| (typeName.equals("java.lang.ApplicationShutdownHooks") && fieldName.equals("hooks"))
				|| (typeName.equals("java.awt.Toolkit") && fieldName.equals("desktopProperties"))
				|| (typeName.equals("java.awt.Toolkit") && fieldName.equals("resources"))
				|| ((typeName.equals("java.awt.font.TextAttribute") || typeName.equals("java.text.AttributedCharacterIterator$Attribute")) && fieldName.equals("instanceMap"))  // Never changes after static initializations.
				|| (typeName.equals("java.awt.RenderingHints$Key") && fieldName.equals("identitymap"))
				|| typeName.equals("java.util.ResourceBundle$RBClassLoader"));  // Never changes after static initializations.
	}
	
	private void getReachableObjects(IJavaValue value, Set<IJavaObject> objs) throws DebugException {
		if (value instanceof IJavaArray) {
			IJavaArray arr = (IJavaArray)value;
			if (objs.add(arr) && !EclipseUtils.isPrimitive(Signature.getElementType(arr.getSignature())) && !arr.getSignature().equals("[Ljava/lang/String;"))
				for (IJavaValue elem: arr.getValues())
					getReachableObjects(elem, objs);
		} else if (value instanceof IJavaObject) {
			IJavaObject obj = (IJavaObject)value;
			if (objs.add(obj)) {
				// We use the internal API here because the Eclipse one must get each field's value one-by-one and so is much slower.
				ObjectReference obj2 = ((JDIObjectValue)obj).getUnderlyingObject();
				if (obj2 != null)  // null values fail this test
					for (Map.Entry<Field, Value> field: obj2.getValues(obj2.referenceType().allFields()).entrySet())
						if (isUsefulStaticDFSField(field.getKey().declaringType().name(), field.getKey()))
							getReachableObjects(JDIValue.createValue((JDIDebugTarget)stack.getDebugTarget(), field.getValue()), objs);
			}
		}
	}
	
	private Set<IField> getFields(Map<String, ArrayList<IJavaObject>> instancesCache, SubMonitor monitor) {
		try {
			//long startTime = System.currentTimeMillis();
			List<ReferenceType> loadedTypes = getAllLoadedTypes(stack);
			monitor.setWorkRemaining(loadedTypes.size());
			Set<IJavaObject> objs = getAllReachableObjects(monitor);
			Set<String> types = new HashSet<String>();
			Map<String, IType> itypes = new HashMap<String, IType>();
			Set<IField> fields = new HashSet<IField>();
			// Add fields from reachable locals.
			monitor.setWorkRemaining(objs.size());
			for (IJavaObject obj: objs) {
				String typeName = EclipseUtils.removeGenerics(obj.getReferenceTypeName());
				if (types.add(typeName) && isUsefulType(typeName))
					for (IVariable field: obj.getVariables())
						if (field instanceof IJavaFieldVariable)
							addField(((JDIFieldVariable)field).getDeclaringType().getName(), field.getName(), typeName, fields, itypes);
				for (IJavaType type = obj.getJavaType(); type instanceof IJavaClassType; type = ((IJavaClassType)type).getSuperclass())
					Utils.addToListMap(instancesCache, type.getName(), obj);
				long id = obj.getUniqueId();
				if (id > maxID)
					maxID = id;
				monitor.worked(1);
			}
			// Add static fields from loaded classes.
			for (ReferenceType type: loadedTypes)
				if (isUsefulType(type.name()))
					for (Field field: type.fields())
						if (field.isStatic())
							addField(type.name(), field.name(), type.name(), fields, itypes);
			//System.out.println("maxID: " + maxID);
			//System.out.println("fields: " + (System.currentTimeMillis() - startTime));
			/*for (IField field: fields)
				System.out.println(field.getDeclaringType().getFullyQualifiedName() + "." + field.getElementName() + ": " + instancesCache.get(field.getDeclaringType().getFullyQualifiedName()));*/
			//System.out.println(fields.size() + " fields");
			return fields;
		} catch (DebugException e) {
			throw new RuntimeException(e);
		} catch (JavaModelException e) {
			throw new RuntimeException(e);
		}
	}

	private void addField(String declName, String fieldName, String objTypeName, Set<IField> fields, Map<String, IType> itypes) throws JavaModelException {
		if (isUsefulType(declName) && isUsefulField(declName, fieldName, objTypeName)) {
			IType itype = itypes.get(declName);
			if (itype == null) {
				itype = project.findType(declName, (IProgressMonitor)null);
				itypes.put(declName, itype);
			}
			if (itype != null) {
				IField ifield = itype.getField(fieldName);
				if (ifield.exists() && (!Flags.isFinal(ifield.getFlags()) || canBeArray(ifield)))
					fields.add(ifield);
			}
		}
	}

	/*private Set<IJavaObject> printAllReachableObjects(SubMonitor monitor) {
		try {
			Set<IJavaObject> objs = new HashSet<IJavaObject>();
			printReachableObjects(stack.getThis(), objs, "this");
			for (IVariable var: stack.getLocalVariables())
				printReachableObjects((IJavaValue)var.getValue(), objs, var.getName());
			for (ReferenceType type: getAllLoadedTypes(stack)) {
				for (Field field: type.allFields())
					if (field.isStatic() && isUsefulStaticDFSField(type.name(), field))
						printReachableObjects(JDIValue.createValue((JDIDebugTarget)stack.getDebugTarget(), type.getValue(field)), objs, type.name() + "." + field.name());
				monitor.worked(1);
			}
			return objs;
		} catch (DebugException e) {
			throw new RuntimeException(e);
		}
	}
	
	private void printReachableObjects(IJavaValue value, Set<IJavaObject> objs, String path) throws DebugException {
		if (value != null && !objs.contains(value) && value.toString().contains("HashMap"))
			System.out.println(path + ": " + value);
		if (value instanceof IJavaArray) {
			IJavaArray arr = (IJavaArray)value;
			if (objs.add(arr) && !EclipseUtils.isPrimitive(Signature.getElementType(arr.getSignature())) && !arr.getSignature().equals("[Ljava/lang/String;")) {
				int i = 0;
				for (IJavaValue elem: arr.getValues())
					printReachableObjects(elem, objs, path + "[" + i++ + "]");
			}
		} else if (value instanceof IJavaObject) {
			IJavaObject obj = (IJavaObject)value;
			if (objs.add(obj)) {
				// We use the internal API here because the Eclipse one must get each field's value one-by-one and so is much slower.
				ObjectReference obj2 = ((JDIObjectValue)obj).getUnderlyingObject();
				if (obj2 != null)  // null values fail this test
					for (Map.Entry<Field, Value> field: obj2.getValues(obj2.referenceType().allFields()).entrySet())
						if (isUsefulStaticDFSField(field.getKey().declaringType().name(), field.getKey()))
							printReachableObjects(JDIValue.createValue((JDIDebugTarget)stack.getDebugTarget(), field.getValue()), objs, path + "." + field.getKey().name());
			}
		}
	}*/
	
	private List<MyJavaWatchpoint> getFieldWatchpoints(final Collection<IField> fields, final Map<String, ArrayList<IJavaObject>> instancesCache) {
		try {
			final List<MyJavaWatchpoint> watchpoints = new ArrayList<MyJavaWatchpoint>(fields.size());
			IWorkspaceRunnable wr = new IWorkspaceRunnable() {
				@Override
				public void run(IProgressMonitor monitor) throws CoreException {
					for (IField field: fields) {
						String typeName = field.getDeclaringType().getFullyQualifiedName();
						List<IJavaObject> instances = instancesCache == null || Flags.isStatic(field.getFlags()) ? null : instancesCache.get(typeName);
						if (instances != null && (instances.size() == 1 || typeName.equals("java.lang.AbstractStringBuilder")  // Special case: Use instance filters for StringBuilders, since they come up a lot and slow us down a lot.
								|| (typeName.startsWith("java.util.") && typeName.contains("Map") && instances.size() <= 2)  // HashMap breakpoints can slow some things down a lot, so I want to think about optimizing them like this.
								|| (typeName.equals("java.util.ArrayList") && instances.size() <= 10)  // ArrayList breakpoints can slow some things down a lot, so I want to think about optimizing them like this.
								|| (typeName.equals("java.util.Vector") && instances.size() <= 10)  // Vector breakpoints can slow some things down a lot, so I want to think about optimizing them like this.
								|| (typeName.equals("java.util.Hashtable") && instances.size() <= 10)  // Hashtable breakpoints can slow some things down a lot, so I want to think about optimizing them like this.
								|| (typeName.equals("java.util.Arrays$ArrayList") && instances.size() <= 10)  // Arrays$ArrayList breakpoints can slow some things down a lot, so I want to think about optimizing them like this.
								|| (typeName.equals("java.util.AbstractList") && instances.size() <= 20)))  // AbstractList breakpoints can slow some things down a lot, so I want to think about optimizing them like this.
							for (IJavaObject instance: instances)
								watchpoints.add(new MyJavaWatchpoint(field, instance));
						else
							watchpoints.add(new MyJavaWatchpoint(field, null));
					}
					DebugPlugin.getDefault().getBreakpointManager().addBreakpoints(watchpoints.toArray(new IBreakpoint[watchpoints.size()]));
				}
			};
			if (!fields.isEmpty()) {
				//long startTime = System.currentTimeMillis();
				ResourcesPlugin.getWorkspace().run(wr, null);
				/*if (instancesCache != null)  // Only print time for initial installation, not ones on load.
					System.out.println("run: " + (System.currentTimeMillis() - startTime));*/
			}
			//System.out.println("Installed " + watchpoints.size() + " breakpoints.");
			return watchpoints;
		} catch (CoreException ex) {
			throw new RuntimeException(ex);
		}
	}
	
	private List<IJavaMethodEntryBreakpoint> getReflectionBreakpoints(IJavaProject project) {
		try {
			List<IJavaMethodEntryBreakpoint> reflectionBreakpoints = new ArrayList<IJavaMethodEntryBreakpoint>();
			// We don't need to instrument to Array methods because they need to read the array, so we will already catch them.
			IType fieldType = project.findType("java.lang.reflect.Field");
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("set", new String[] { "Ljava.lang.Object;", "Ljava.lang.Object;" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setBoolean", new String[] { "Ljava.lang.Object;", "Z" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setByte", new String[] { "Ljava.lang.Object;", "B" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setChar", new String[] { "Ljava.lang.Object;", "C" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setShort", new String[] { "Ljava.lang.Object;", "S" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setInt", new String[] { "Ljava.lang.Object;", "I" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setLong", new String[] { "Ljava.lang.Object;", "J" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setFloat", new String[] { "Ljava.lang.Object;", "F" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("setDouble", new String[] { "Ljava.lang.Object;", "D" })));
			reflectionBreakpoints.add(new ReflectionBreakpoint(fieldType.getMethod("get", new String[] { "Ljava.lang.Object;" })));
			return reflectionBreakpoints;
		} catch (CoreException e) {
			throw new RuntimeException(e);
		}
	}
	
	// Use a common interface so we can recognize our own breakpoints.
	public static interface SideEffectBreakpoint {
		
	}
	
	private class MyJavaWatchpoint extends JavaWatchpoint implements SideEffectBreakpoint {
		
		private final boolean canBeArray;
		private final boolean isFinal;
		private final boolean canDisable;
		
		public MyJavaWatchpoint(IField field, IJavaObject instance) throws CoreException {
			//super(BreakpointUtils.getBreakpointResource(field.getDeclaringType()), field.getDeclaringType().getElementName(), field.getElementName(), -1, -1, -1, 0, false, new HashMap<String, Object>(10));
			
			canBeArray = canBeArray(field);
			isFinal = Flags.isFinal(field.getFlags());
			canDisable = Flags.isStatic(field.getFlags()) || instance != null;

			IType type = field.getDeclaringType();
			IResource resource = BreakpointUtils.getBreakpointResource(field.getDeclaringType());
			String typeName = type.getFullyQualifiedName();
			String fieldName = field.getElementName();
			Map<String, Object> attributes = new HashMap<String, Object>(10);
			
			// Copied from JavaWatchpoint's constructor.
			setMarker(resource.createMarker(JavaWatchpoint.JAVA_WATCHPOINT));
			addLineBreakpointAttributes(attributes, getModelIdentifier(), true, -1, -1, -1);
			addTypeNameAndHitCount(attributes, typeName, 0);
			attributes.put(SUSPEND_POLICY, new Integer(getDefaultSuspendPolicy()));
			addFieldName(attributes, fieldName);
			addDefaultAccessAndModification(attributes);
			ensureMarker().setAttributes(attributes);
			
			if (instance != null)  // It seems like you can only add one instance filter (https://bugs.eclipse.org/bugs/show_bug.cgi?id=32842).
				addInstanceFilter(instance);
		}

		@Override
		protected boolean[] getDefaultAccessAndModificationValues() {
			return new boolean[] { canBeArray, !isFinal };  // We do not want to break on accesses to non-array fields or modifications to final fields.
		}

		@Override
		public boolean handleEvent(Event event, JDIDebugTarget target, boolean suspendVote, EventSet eventSet) {
			/*if (event instanceof WatchpointEvent)
				System.out.println(event + " on " + FieldLVal.makeFieldLVal(((WatchpointEvent)event).object(), ((WatchpointEvent)event).field()).toString());
			else if (event instanceof com.sun.jdi.event.ClassPrepareEvent)
				System.out.println("Prep " + ((com.sun.jdi.event.ClassPrepareEvent)event).referenceType().name());
			else
				System.out.println(event);*/
			synchronized (SideEffectHandler.this) {
				if (effectsMap == null)  // We're not currently tracking side effects.
					return true;
				if (event instanceof WatchpointEvent) {
					WatchpointEvent watchEvent = (WatchpointEvent)event;
					//System.out.println(event + " on " + FieldLVal.makeFieldLVal(watchEvent.object(), watchEvent.field()).toString());
					ObjectReference obj = watchEvent.object();
					if (obj != null && obj.uniqueID() > maxID) {
						//System.out.println("Ignoring new object " + obj.toString());
						return true;
					}
					if (watchEvent instanceof ModificationWatchpointEvent) {
						ModificationWatchpointEvent modEvent = (ModificationWatchpointEvent)watchEvent;
						if (!modEvent.location().method().isStaticInitializer())  // If the field is modified in a static initializer, it must be the initialization of a static field of a newly-loaded class, which we don't want to revert.
							recordEffect(FieldLVal.makeFieldLVal(obj, modEvent.field()), modEvent.valueCurrent(), modEvent.valueToBe());
						return true;
					} else if (watchEvent instanceof AccessWatchpointEvent) {
						AccessWatchpointEvent readEvent = (AccessWatchpointEvent)watchEvent;
						Value oldVal = readEvent.valueCurrent();
						if (oldVal instanceof ArrayReference) {
							FieldLVal lval = FieldLVal.makeFieldLVal(obj, readEvent.field());
							backupArray(lval, oldVal);
							if (canDisable && !changedFields.contains(lval) && Utils.incrementMap(accessCounts, this) >= 10) {  // Disabling is slow, so we only do it for frequently-accessed fields.
								try {
									disabledWatchpoints.add(this);
									setEnabled(false);
								} catch (CoreException e) {
									throw new RuntimeException(e);
								}
							}
						}
						/*else
						System.out.println("Ignoring read on non-array Object");*/
						return true;
					}
				}
				return super.handleEvent(event, target, suspendVote, eventSet);
			}
		}
		
	}

	private synchronized void recordEffect(FieldLVal lval, Value oldVal, Value newVal) {
		if (oldVal != newVal && (oldVal == null || !oldVal.equals(newVal))) {
			if (readArrays.contains(lval))  // If the static type of the field is Object, we might track it twice, in write and array read.
				return;
			RVal newRVal = RVal.makeRVal(newVal);
			if (!effectsMap.containsKey(lval)) {
				changedFields.add(lval);
				RVal oldRVal = RVal.makeRVal(oldVal);
				effectsMap.put(lval, new MutablePair<RVal, RVal>(oldRVal, newRVal));
				disableCollection(oldVal);
			} else {
				MutablePair<RVal, RVal> oldEffect = effectsMap.get(lval);
				if (oldEffect.first.equals(newRVal)) {
					effectsMap.remove(lval);
					enableCollection(oldVal);
				} else
					oldEffect.second = newRVal;
			}
			//System.out.println("Changing " + lval.toString() + " from " + oldVal + " to " + newVal);
		}/* else
			System.out.println("Unchanged " + lval.toString() + " from " + oldVal);*/
	}

	private synchronized void backupArray(FieldLVal lval, Value oldVal) {
		if (changedFields.contains(lval))  // If the static type of the field is Object, we might track it twice, in write and array read.
			return;
		if (!readFieldsMap.containsKey(lval)) {
			readArrays.add(lval);
			ArrayValue oldRVal = ArrayValue.makeArrayValue((ArrayReference)oldVal);
			readFieldsMap.put(lval, oldRVal);
			disableCollection(oldVal);
			//System.out.println("Reading " + lval.toString() + " from " + (oldVal == null ? "null" : getValues((ArrayReference)oldVal)));
		}
	}
	
	private synchronized Set<Effect> getEffects() {
		Set<Effect> effects = new HashSet<Effect>();
		for (Map.Entry<FieldLVal, MutablePair<RVal, RVal>> entry: effectsMap.entrySet()) {
			// We need to update the new rval if it's an array because its entries might have changed.
			Effect effect = new Effect(entry.getKey(), entry.getValue().first, updateRVal(entry.getValue().second));
			effects.add(effect);
		}
		for (Map.Entry<FieldLVal, ArrayValue> entry: readFieldsMap.entrySet()) {
			LVal lval = entry.getKey();
			ArrayValue initialVal = entry.getValue();
			Value newVal = lval.getValue();
			Effect effect = new Effect(lval, initialVal, RVal.makeRVal(newVal));
			if (newVal instanceof ArrayReference) {
				ArrayReference newValArray = (ArrayReference)newVal;
				if (!initialVal.equals(newValArray))
					effects.add(effect);
				else {
					//System.out.println("Unchanged " + lval.toString() + " from " + initialVal);
					enableCollection(initialVal.getValue());
				}
			} else
				effects.add(effect);
		}
		// We do arg arrs last, since they might have already been handled by one of the above mechanisms (e.g., passing a field as an array).  I could try to optimize it so that I know if such a case has happened and avoid the extra equality check.
		for (Pair<ArrayReference, ArrayValue> info: argArrs) {
			if (!info.second.equals(info.first)) {
				Effect effect = new Effect(new ArgArrLVal(info.first), info.second, RVal.makeRVal(info.first));
				effects.add(effect);
			} else {
				enableCollection(info.first);
				//System.out.println("Unchanged arg arr from " + info.second);*/
			}
		}
		for (Effect effect: manualEffects)
			effects.add(effect);
		for (Effect effect: effects) {
			disableCollection(effect.getNewVal().getValue());
			storeCollectionDisableds(effect.getOldVal().getValue());
			storeCollectionDisableds(effect.getNewVal().getValue());
		}
		/*for (Effect effect: effects)
			System.out.println("Effect: " + effect);*/
		return effects;
	}
	
	public static Set<Effect> undoEffects(Set<Effect> effects) {
		try {
			for (Effect effect: effects) {
				//System.out.println("Resetting " + effect);
				effect.undo();
			}
			return effects;
		} catch (InvalidTypeException e) {
			throw new RuntimeException(e);
		} catch (ClassNotLoadedException e) {
			throw new RuntimeException(e.className(), e);
		}
	}
	
	private static RVal updateRVal(RVal rval) {
		if (rval instanceof ArrayValue)
			return RVal.makeRVal(rval.getValue());
		else
			return rval;
	}
	
	// Work around a bug where getValues() crashes when called on an empty array.
	protected static List<Value> getValues(ArrayReference value) {
		if (value.length() == 0)
			return new ArrayList<Value>(0);
		else
			return value.getValues();
	}
	
	// Dealing with array values, which can be nested.
	
	private class MyClassPrepareBreakpoint extends JavaClassPrepareBreakpoint implements SideEffectBreakpoint {
		
		private final IJavaProject project;
		
		public MyClassPrepareBreakpoint(IJavaProject project) throws DebugException {
			super(ResourcesPlugin.getWorkspace().getRoot(), "*", IJavaClassPrepareBreakpoint.TYPE_CLASS, -1, -1, true, new HashMap<String, Object>(10));
			this.project = project;
		}
		
		@Override
		public boolean handleClassPrepareEvent(ClassPrepareEvent event, JDIDebugTarget target, boolean suspendVote) {
			//long startTime = System.currentTimeMillis();
			//System.out.println("Prepare " + event.referenceType().name());
			try {
				IType itype = project.findType(event.referenceType().name(), (IProgressMonitor)null);
				List<IField> fields = new ArrayList<IField>();
				if (itype == null) {
					//System.out.println("Bad times on " + event.referenceType().name());
				} else {
					String typeName = itype.getFullyQualifiedName();
					if (isUsefulType(typeName))
						for (IField field: itype.getFields())
							if ((!Flags.isFinal(field.getFlags()) || field.getTypeSignature().contains("[")) && Flags.isStatic(field.getFlags())
									&& isUsefulField(typeName, field.getElementName(), typeName))
								fields.add(field);
					List<MyJavaWatchpoint> newWatchpoints = getFieldWatchpoints(fields, null);
					addedWatchpoints.addAll(newWatchpoints);
				}
				//System.out.println("Prepared " + event.referenceType() + " in " + (System.currentTimeMillis()- startTime) + "ms.");
			} catch (JavaModelException e) {
				throw new RuntimeException(e);
			}
			return super.handleClassPrepareEvent(event, target, suspendVote);
		}
		
	}
	
	private boolean enabled;
	
	private List<MyJavaWatchpoint> addedWatchpoints;
	private MyClassPrepareBreakpoint prepareBreakpoint;
	private List<IJavaMethodEntryBreakpoint> reflectionBreakpoints;
	
	private Map<FieldLVal, MutablePair<RVal, RVal>> effectsMap;
	private Map<FieldLVal, ArrayValue> readFieldsMap;
	private List<Pair<ArrayReference, ArrayValue>> argArrs;
	private Set<FieldLVal> changedFields;
	private Set<FieldLVal> readArrays;
	private Set<ArrayReference> backedUpArrays;
	private Set<Effect> manualEffects;
	private Map<MyJavaWatchpoint, Integer> accessCounts;
	private List<MyJavaWatchpoint> disabledWatchpoints;
	
	public synchronized void startHandlingSideEffects() {
		if (!enabled)
			return;
		if (effectsMap == null) {
			effectsMap = new HashMap<FieldLVal, MutablePair<RVal, RVal>>();
			readFieldsMap = new HashMap<FieldLVal, ArrayValue>();
			argArrs = new ArrayList<Pair<ArrayReference,ArrayValue>>();
			changedFields = new HashSet<FieldLVal>();
			readArrays = new HashSet<FieldLVal>();
			backedUpArrays = new HashSet<ArrayReference>();
			manualEffects = new HashSet<Effect>();
			accessCounts = new HashMap<MyJavaWatchpoint, Integer>();
			disabledWatchpoints = new ArrayList<MyJavaWatchpoint>();
		}
	}

	public void checkArguments(IJavaValue[] argValues) {
		if (!enabled)
			return;
		for (IJavaValue argValue: argValues) {
			checkArgument(argValue);
		}
	}

	public void checkArgument(IJavaValue argValue) {
		if (!enabled)
			return;
		if (argValue instanceof IJavaArray) {
			ArrayReference arr = (ArrayReference)((JDIObjectValue)argValue).getUnderlyingObject();
			if (backedUpArrays.add(arr)) {
				//System.out.println("Backing up arg arr " + (arr == null ? "null" : getValues(arr)));
				argArrs.add(new Pair<ArrayReference, ArrayValue>(arr, ArrayValue.makeArrayValue(arr)));
				disableCollection(arr);
			}
		}
	}
	
	public Set<Effect> getSideEffects() {
		if (!enabled)
			return Collections.emptySet();
		Set<Effect> effects = getEffects();
		if (effects.isEmpty())
			return Collections.emptySet();
		else
			return effects;
	}
	
	public synchronized Set<Effect> stopHandlingSideEffects() {
		if (!enabled)
			return Collections.emptySet();
		Set<Effect> effects = getSideEffects();
		effectsMap = null;
		readFieldsMap = null;
		argArrs = null;
		changedFields = null;
		readArrays = null;
		backedUpArrays = null;
		manualEffects = null;
		try {
			for (MyJavaWatchpoint wp: disabledWatchpoints) {
				wp.setEnabled(true);
			}
		} catch (CoreException e) {
			throw new RuntimeException(e);
		}
		accessCounts = null;
		disabledWatchpoints = null;
		undoEffects(effects);
		return effects;
	}
	
	public boolean isHandlingSideEffects() {
		return addedWatchpoints != null;
	}
	
	public void stop(IProgressMonitor monitor) {
		if (!enabled)
			return;
		try {
			IProgressMonitor curMonitor = SubMonitor.convert(monitor, "Side effect handler cleanup", (prepareBreakpoint == null ? 0 : 1) + (addedWatchpoints == null ? 0 : addedWatchpoints.size()) + (reflectionBreakpoints == null ? 0 : reflectionBreakpoints.size()));
			//long startTime = System.currentTimeMillis();
			if (prepareBreakpoint != null) {  // We disable this before addedWatchpoints since it could add new breakpoints to it.
				prepareBreakpoint.delete();
				curMonitor.worked(1);
				prepareBreakpoint = null;
			}
			if (addedWatchpoints != null) {
				DebugPlugin.getDefault().getBreakpointManager().removeBreakpoints(addedWatchpoints.toArray(new IBreakpoint[addedWatchpoints.size()]), true);
				curMonitor.worked(addedWatchpoints.size());
				addedWatchpoints = null;
			}
			if (reflectionBreakpoints != null) {
				DebugPlugin.getDefault().getBreakpointManager().removeBreakpoints(reflectionBreakpoints.toArray(new IBreakpoint[reflectionBreakpoints.size()]), true);
				curMonitor.worked(reflectionBreakpoints.size());
				reflectionBreakpoints = null;
			}
			//System.out.println("stop: " + (System.currentTimeMillis() - startTime));
			curMonitor.done();
		} catch (CoreException e) {
			EclipseUtils.showError("Error", "Error", e);
		} catch (RuntimeException e) {
			EclipseUtils.showError("Error", "Error", e);
		}
	}
	
	public void enable(boolean enable) {
		enabled = enable;
	}
	
	public boolean isEnabled() {
		return enabled;
	}
	
	/*public void enableBreakpoints() {
		enableDisableBreakpoints(true);
	}
	
	public void disableBreakpoints() {
		enableDisableBreakpoints(false);
	}
	
	private void enableDisableBreakpoints(boolean enable) {
		try {
			long startTime = System.currentTimeMillis();
			for (IBreakpoint breakpoint: addedWatchpoints)
				breakpoint.setEnabled(enable);
			for (IBreakpoint breakpoint: reflectionBreakpoints)
				breakpoint.setEnabled(enable);
			System.out.println((enable ? "enable" : "disable") + ": " + (System.currentTimeMillis() - startTime));
		} catch (CoreException e) {
			throw new RuntimeException(e);
		}
	}*/

	public static void redoEffects(Set<Effect> effects) {
		try {
			for (Effect effect: effects) {
				//System.out.println("Redoing " + effect);
				effect.redo();
			}
		} catch (InvalidTypeException e) {
			throw new RuntimeException(e);
		} catch (ClassNotLoadedException e) {
			throw new RuntimeException(e);
		}
	}

	public synchronized void redoAndRecordEffects(Set<Effect> effects) {
		for (Effect effect: effects) {
			LVal lval = effect.getLval();
			if (lval instanceof FieldLVal) {
				FieldLVal flval = (FieldLVal)effect.getLval();
				if (!(effect.getOldVal().getValue() instanceof ArrayReference))
					recordEffect(flval, effect.getOldVal().getValue(), effect.getNewVal().getValue());
				else
					backupArray(flval, effect.getOldVal().getValue());
			}
			if (lval instanceof VarLVal || lval instanceof ArrayAccessLVal)
				manualEffects.add(effect);  // These effects happen only from our algorithms and not during evaluations, so we store them manually.
		}
		redoEffects(effects);
	}
	
	private class ReflectionBreakpoint extends JavaMethodEntryBreakpoint implements SideEffectBreakpoint {
		
		public ReflectionBreakpoint(IMethod method) throws JavaModelException, CoreException {
			super(BreakpointUtils.getBreakpointResource(method.getDeclaringType()), method.getDeclaringType().getFullyQualifiedName(), method.getElementName(), method.getSignature(), -1, -1, -1, 0, true, new HashMap<String, Object>(10));
		}

		@Override
		public boolean handleEvent(Event event, JDIDebugTarget target, boolean suspendVote, EventSet eventSet) {
			try {
				//System.out.println("Reflection: " + event);
				ThreadReference thread = ((LocatableEvent)event).thread();
				StackFrame stack = thread.frame(0);
				ObjectReference fieldValue = stack.thisObject();
				ReferenceType fieldType = fieldValue.referenceType();
				//String className = ((ObjectReference)fieldValue.getValue(fieldType.fieldByName("clazz"))).invokeMethod(thread, event.virtualMachine().classesByName("java.lang.Class").get(0).methodsByName("getName").get(0), new ArrayList<Value>(0), 0).toString();  // Calling methods in the child JVM seems to crash here.
				//String className = ((StringReference)((ObjectReference)fieldValue.getValue(fieldType.fieldByName("clazz"))).getValue(event.virtualMachine().classesByName("java.lang.Class").get(0).fieldByName("name"))).value();  // This works in JDK 7 but breaks in JDK 8 (because getting fields no longer calls SecurityManager.checkMemberAccess).
				String className = ((ClassObjectReference)fieldValue.getValue(fieldType.fieldByName("clazz"))).reflectedType().name();
				String fieldName = ((StringReference)fieldValue.getValue(fieldType.fieldByName("name"))).value();
				Field field = event.virtualMachine().classesByName(className).get(0).fieldByName(fieldName);
				List<Value> argValues = stack.getArgumentValues();
				ObjectReference obj = (ObjectReference)argValues.get(0);
				if (!field.isStatic() && obj == null)
					return true;  // The execution will crash.
				Value oldValue = field.isStatic() ? field.declaringType().getValue(field) : obj.getValue(field);
				if (argValues.size() == 2) {  // We're setting the value of a field.
					Value newValue = argValues.get(1);
					if (newValue instanceof ObjectReference && EclipseUtils.isPrimitive(field.signature()))  // Unbox primitive values.
						newValue = ((ObjectReference)newValue).getValue(((ReferenceType)newValue.type()).fieldByName("value"));
					recordEffect(FieldLVal.makeFieldLVal(obj, field), oldValue, newValue);
				} else if (oldValue instanceof ArrayReference)  // We're reading the value of an array.
					backupArray(FieldLVal.makeFieldLVal(obj, field), oldValue);
			} catch (IncompatibleThreadStateException e) {
				throw new RuntimeException(e);
			}
			return true;
		}
		
	}
	
	private List<ObjectReference> collectionDisableds;
	
	private static void enableCollection(Value value) {
		if (value instanceof ObjectReference) {
			ObjectReference obj = ((ObjectReference)value);
			obj.enableCollection();
			if (obj instanceof ArrayReference)  // Recurse on array elements, which might be objects/arrays.
				for (Value elem: getValues((ArrayReference)obj))
					enableCollection(elem);
		}
	}
	
	private static void disableCollection(Value value) {
		if (value instanceof ObjectReference) {
			ObjectReference obj = ((ObjectReference)value);
			obj.disableCollection();
			if (obj instanceof ArrayReference)  // Recurse on array elements, which might be objects/arrays.
				for (Value elem: getValues((ArrayReference)obj))
					disableCollection(elem);
		}
	}
	
	private void storeCollectionDisableds(Value value) {
		if (value instanceof ObjectReference) {
			ObjectReference obj = ((ObjectReference)value);
			collectionDisableds.add(obj);
		}
	}
	
	public void emptyDisabledCollections() {
		for (ObjectReference obj: collectionDisableds)
			enableCollection(obj);
		collectionDisableds = null;
	}

}
