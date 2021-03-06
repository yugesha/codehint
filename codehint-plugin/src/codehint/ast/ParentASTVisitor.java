package codehint.ast;

import java.util.Stack;

public class ParentASTVisitor extends ASTVisitor {
	
	protected final Stack<ASTNode> parents;
	
	protected ParentASTVisitor() {
		parents = new Stack<ASTNode>();
	}

	@Override
	public boolean preVisit(ASTNode node) {
		parents.push(node);
		return true;
	}

	@Override
	public void postVisit(ASTNode node) {
		ASTNode p = parents.pop();
		assert p == node;
	}
	
	protected boolean parentIsName(ASTNode node) {
		ASTNode parent = parents.size() == 1 ? null : parents.elementAt(parents.size() - 2);
		return parent != null && ((parent instanceof MethodInvocation && ((MethodInvocation)parent).getName() == node) || (parent instanceof FieldAccess && ((FieldAccess)parent).getName() == node));
	}

}
