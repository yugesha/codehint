package codehint.property;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import org.eclipse.debug.core.DebugException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.debug.core.IJavaStackFrame;
import org.eclipse.jdt.debug.eval.IAstEvaluationEngine;

import codehint.ast.ASTConverter;
import codehint.ast.ASTFlattener;
import codehint.ast.ASTNode;
import codehint.ast.ASTVisitor;
import codehint.ast.CompilationUnit;
import codehint.ast.Expression;
import codehint.ast.MethodInvocation;
import codehint.ast.SimpleName;
import codehint.utils.EclipseUtils;

/**
 * Note that variables without primes default to pre.
 */
public class StateProperty extends Property {
	
    private final static ASTParser parser = ASTParser.newParser(AST.JLS4);

	private final String lhs;
	private final Expression property;
	private final String propertyStr;
	private Set<String> cachedPreVariables;
	
	private StateProperty(String lhs, Expression property, String propertyStr) {
		this.lhs = lhs;
		this.property = property;
		this.propertyStr = propertyStr;
	}
	
	public static StateProperty fromPropertyString(String lhs, String propertyStr) {
		Expression property = (Expression)rewritePrimeSyntax(propertyStr);
		return new StateProperty(lhs, property, propertyStr);
	}
	
	public static String isLegalProperty(String str, IJavaStackFrame stackFrame, IAstEvaluationEngine evaluationEngine) {
    	if ("".equals(str))
    		return "Please enter a property.";
		ASTNode property = rewritePrimeSyntax(str);
		if (property instanceof CompilationUnit)
			return "Enter a valid expression: " + ((CompilationUnit)property).getProblems()[0].getMessage();
		String compileErrors = EclipseUtils.getCompileErrors(property.toString().replaceAll("CodeHint.post", ""), "boolean", stackFrame, evaluationEngine);
		if (compileErrors != null)
			return compileErrors;
		return ValidityChecker.getError((Expression)property);
	}
	
	private static ASTNode rewritePrimeSyntax(String str) {
		while (true) {
			ASTNode node = ASTConverter.parseExpr(parser, str);
	    	if (node instanceof CompilationUnit) {
	    		IProblem problem = ((CompilationUnit)node).getProblems()[0];
	    		int problemStart = problem.getSourceStart();
	    		if (problem.getID() == IProblem.InvalidCharacterConstant && problemStart < str.length() && str.charAt(problemStart) == '\'') {
	    			str = rewriteSinglePrime(str, problemStart);
	    		} else
	    			return node;
	    	} else
	    		return node;
		}
		
	}

	public static String rewriteSinglePrime(String str, int problemStart) {
		int varStart = problemStart - 1;
		while (varStart >= 0 && Character.isJavaIdentifierPart(str.charAt(varStart)))
			varStart--;
		str = str.substring(0, varStart + 1) + "CodeHint.post(" + str.substring(varStart + 1, problemStart) + ")" + str.substring(problemStart + 1);
		return str;
	}
	
	public Set<String> getPreVariables(IJavaStackFrame stack) {
		if (cachedPreVariables == null) {
			PreVariableFinder preFinder = new PreVariableFinder(stack);
			property.accept(preFinder);
			cachedPreVariables = preFinder.getPreVariables();
		}
		return cachedPreVariables;
	}

	/**
	 * Gets a string representing the evaluation of this
	 * property with lambda argument replaced by the given string.
	 * @param arg Actual argument.
	 * @return  string representing the evaluation of this
	 * property with lambda argument replaced by the given string.
	 */
	@Override
	public String getReplacedString(String arg, IJavaStackFrame stack) {
		return (new StateASTFlattener(lhs, arg, stack)).getResult(property);
	}
	
	public static String getRenamedVar(String name) {
		return "_$pre_$" + name;
	}
	
	private static boolean isPre(MethodInvocation node) {
		return isPreOrPost(node, "pre");
	}
	
	private static boolean isPost(MethodInvocation node) {
		return isPreOrPost(node, "post");
	}
	
	private static boolean isPreOrPost(MethodInvocation node, String name) {
		return node.getExpression() instanceof SimpleName && "CodeHint".equals(((SimpleName)node.getExpression()).getIdentifier()) && name.equals(node.getName().getIdentifier());
	}
	
	public String getPropertyString() {
		return propertyStr;
	}
	
	@Override
	public String toString() {
		return propertyStr;
	}
	
	private static class ValidityChecker extends ASTVisitor {
		
		private String error;
		
		private ValidityChecker() {
			error = null;
		}

    	@Override
    	public boolean visit(MethodInvocation node) {
    		if (isPre(node) || isPost(node)) {
	    		if (node.arguments().length != 1)
	    			error = "Call to " + node.getName() + " must take in only a variable, not " + Arrays.toString(node.arguments());
	    		else if (!(node.arguments()[0] instanceof SimpleName))
	    			error = "Call to " + node.getName() + " must take in only a variable, not " + node.arguments()[0].toString();
    		}
    		return true;
    	}
    	
    	public static String getError(Expression property) {
    		ValidityChecker checker = new ValidityChecker();
    		property.accept(checker);
    		return checker.error;
    	}
		
	}
	
	private static class PreVariableFinder extends ASTVisitor {
		
		private final IJavaStackFrame stack;
		private final Set<String> preVariables;
		
		public PreVariableFinder(IJavaStackFrame stack) {
			this.stack = stack;
			preVariables = new HashSet<String>();
		}

		@Override
    	public boolean visit(MethodInvocation node) {
    		if (isPre(node)) {
	    		preVariables.add(((SimpleName)node.arguments()[0]).getIdentifier());
	    		return false;
    		}else if (isPost(node))
    			return false;
    		return true;
    	}
		
		@Override
		public boolean visit(SimpleName node) {
			try {
				if (stack.findVariable(node.getIdentifier()) != null)
					preVariables.add(node.getIdentifier());
			} catch (DebugException e) {
				throw new RuntimeException(e);
			}
			return true;
		}

    	public Set<String> getPreVariables() {
			return preVariables;
		}
		
	}
	
	private static final class StateASTFlattener extends ASTFlattener {

		private final String lhs;
		private final String arg;
		private final IJavaStackFrame stack;
		
		public StateASTFlattener(String lhs, String arg, IJavaStackFrame stack) {
			this.lhs = lhs;
			this.arg = arg;
			this.stack = stack;
		}
		
		@Override
    	protected void flatten(MethodInvocation node, StringBuilder sb) {
			if (isPre(node)) {
				sb.append(getRenamedVar(((SimpleName)node.arguments()[0]).getIdentifier()));
			} else if (isPost(node)) {
				String nodeId = ((SimpleName)node.arguments()[0]).getIdentifier();
				sb.append(nodeId.equals(lhs) ? arg : nodeId);
			} else
				super.flatten(node, sb);
		}
		
		@Override
		protected void flatten(SimpleName node, StringBuilder sb) {
			try {
				if (stack.findVariable(node.getIdentifier()) != null) {
					sb.append(getRenamedVar(node.getIdentifier()));
				} else
					super.flatten(node, sb);
			} catch (DebugException e) {
				throw new RuntimeException(e);
			}
		}
		
	}

}
