package codehint.dialogs;

import org.eclipse.debug.core.DebugException;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.core.IJavaValue;
import org.eclipse.swt.widgets.Shell;

import codehint.Synthesizer;
import codehint.expreval.EvaluationManager.EvaluationError;
import codehint.property.PrimitiveValueProperty;
import codehint.property.Property;
import codehint.utils.EclipseUtils;

public class PrimitiveValuePropertyDialog extends ValuePropertyDialog {
	
	private final IJavaStackFrame stack;

	public PrimitiveValuePropertyDialog(String varName, String varTypeName, IJavaStackFrame stack, Shell shell, String initialValue, String extraMessage, boolean getSkeleton) {
		super(varName, varTypeName, stack, shell, initialValue, extraMessage, getSkeleton);
		this.stack = stack;
	}

	@Override
	public Property getProperty() {
		String pdspecText = getPdspecText();
		if (pdspecText == null)
			return null;
		else {
    		try {
		    	IJavaValue demonstrationValue = EclipseUtils.evaluate(pdspecText, stack);
		    	return PrimitiveValueProperty.fromPrimitive(EclipseUtils.javaStringOfValue(demonstrationValue), demonstrationValue);
    		} catch (EvaluationError e) {
		    	Synthesizer.setLastCrashedInfo(varName, PrimitiveValueProperty.fromPrimitive(pdspecText, null), null);
				throw e;
			} catch (DebugException e) {
				throw new RuntimeException(e);
			}
		}
	}

}
