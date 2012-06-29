package codehint.exprgen.typeconstraint;

import org.eclipse.jdt.debug.core.IJavaDebugTarget;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaType;

import codehint.exprgen.SubtypeChecker;

public abstract class TypeConstraint {
	
	public abstract boolean isFulfilledBy(IJavaType type, SubtypeChecker subtypeChecker, IJavaStackFrame stack, IJavaDebugTarget target);
	
	public abstract IJavaType[] getTypes(IJavaDebugTarget target);

}
