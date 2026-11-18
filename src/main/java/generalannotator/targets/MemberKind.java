package generalannotator.targets;

/** The kind of Specimin target member. */
public enum MemberKind {
    /** A method; passed to Specimin as {@code --targetMethod}. */
    METHOD,
    /** A constructor; passed to Specimin as {@code --targetMethod} with the class's simple name. */
    CONSTRUCTOR,
    /** A field; passed to Specimin as {@code --targetField}. */
    FIELD
}
