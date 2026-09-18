package works.nuty.codon.core.model;

/**
 * A specific line within a datapack function. Doubles as the identity of a function breakpoint.
 */
public record FunctionLocation(FunctionId function, int line) {
}
