package codehint.exprgen.precondition;

import java.util.ArrayList;

import codehint.expreval.EvaluatedExpression;
import codehint.exprgen.TypedExpression;

public class GE extends Predicate {
	
	private final Value cur;
	private final Value target;

	/**
	 * Ensures that the first value is greater than or equal to the second.
	 * @param cur The first value.
	 * @param target The second value.
	 */
	public GE(Value cur, Value target) {
		this.cur = cur;
		this.target = target;
	}

	@Override
	public boolean satisfies(TypedExpression receiver, ArrayList<EvaluatedExpression> actuals) {
		return cur.getValue(receiver, actuals) >= target.getValue(receiver, actuals);
	}

	@Override
	public String toString() {
		return cur + " >= " + target;
	}

}
