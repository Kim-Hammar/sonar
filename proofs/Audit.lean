/-
Prints the axioms that each formalized statement of the paper depends on; `check.sh` fails if
any is not one of Lean's standard axioms. Add a `#print axioms` line for every theorem that
corresponds to a statement in the paper.
-/
import Sonar

-- Proposition 1: existence of an optimal search policy.
#print axioms Sonar.SearchModel.exists_optimal_policy
