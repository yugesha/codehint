package codehint.handler;

import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.model.IVariable;
import org.eclipse.jdt.debug.core.IJavaType;
import org.eclipse.jdt.debug.core.IJavaVariable;
import org.eclipse.swt.widgets.Shell;

import codehint.EclipseUtils;
import codehint.Synthesizer;
import codehint.property.LambdaProperty;
import codehint.property.Property;
import codehint.property.ValueProperty;

public class DemonstrateLambdaPropertyHandler extends CommandHandler {
    
    @Override
	public void handle(IVariable variable, String path, Shell shell) {
    	try {
	    	Property lastCrashedProperty = Synthesizer.getLastCrashedProperty(path);
	    	String initValue = lastCrashedProperty instanceof LambdaProperty && !(lastCrashedProperty instanceof ValueProperty) ? lastCrashedProperty.toString() : null;
	    	IJavaType varStaticType = ((IJavaVariable)variable).getJavaType();
	    	LambdaProperty property = EclipseUtils.getLambdaProperty(path, shell, varStaticType, initValue, null, EclipseUtils.getStackFrame());
	    	if (property != null)
	        	Synthesizer.synthesizeAndInsertExpressions(variable, path, property, shell, false);
		} catch (DebugException e) {
			throw new RuntimeException(e);
		}
    }

}