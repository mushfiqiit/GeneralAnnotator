package generalannotator.context;

/**
 * Syntactic facts noticed at an evidence site. They are observations to inform a proposer, not
 * proofs of which annotation is correct.
 */
public enum Observation {
    /** The expression of interest is the {@code null} literal. */
    NULL_LITERAL,
    /** A conditional expression with a {@code null} branch. */
    CONDITIONAL_WITH_NULL_BRANCH,
    /** The value is compared with {@code null}. */
    NULL_COMPARISON,
    /** The value is dereferenced (member access, call, array access, iteration, lock, throw, switch). */
    DEREFERENCE,
    /** A syntactic null check of the same variable guards the site (enclosing condition, early exit, assert). */
    GUARDED_BY_NULL_CHECK,
    /** The value is passed as an argument. */
    PASSED_AS_ARGUMENT,
    /** A {@code null} literal is passed as an argument. */
    NULL_ARGUMENT,
    /** The value is returned. */
    RETURNED,
    /** The value is stored in a variable or field. */
    ASSIGNED,
    /** The value is discarded (expression statement). */
    DISCARDED,
    /** The site is inside a lambda expression. */
    IN_LAMBDA,
    /** The site is inside an anonymous or local class. */
    IN_ANONYMOUS_OR_LOCAL_CLASS,
    /** The site is inside a constructor. */
    IN_CONSTRUCTOR,
    /** The site is inside an initializer block or a field initializer. */
    IN_INITIALIZER,
    /** The call is inserted by the compiler (implicit {@code super()}). */
    IMPLICIT_CALL,
    /** At least one argument has an erroneous (unresolved) type, so overload resolution may be a recovery guess. */
    ERRONEOUS_ARGUMENT_TYPES,
    /** The field is read and written by the same expression ({@code f += x}, {@code f++}). */
    READ_MODIFY_WRITE
}
