package codehint.exprgen.precondition;

import java.util.ArrayList;

import codehint.ast.Expression;
import codehint.exprgen.ExpressionMaker;

public class Const extends Value {
	
	private final int val;

	/**
	 * Creates a constant value.
	 * @param val The constant.
	 */
	public Const(int val) {
		this.val = val;
	}

	@Override
	public int getValue(Expression receiver, ArrayList<Expression> actuals, ExpressionMaker expressionMaker) {
		return val;
	}

}
