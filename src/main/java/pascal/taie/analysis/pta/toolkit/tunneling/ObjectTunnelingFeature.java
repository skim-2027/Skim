package pascal.taie.analysis.pta.toolkit.tunneling;

/**
 * Atomic method features from Table 1 of the OOPSLA'18 context-tunneling
 * paper. Their order intentionally follows the artifact's Feature0-Feature45
 * numbering: the positive literal of feature {@code n} is {@code 2*n}, and
 * its negation is {@code 2*n+1}.
 */
public enum ObjectTunnelingFeature {

    A1, A2, A3, A4, A5, A6, A7, A8, A9, A10,
    B1, B2, B3, B4, B5, B6, B7, B8, B9, B10, B11, B12, B13;

    public int mask() {
        return 1 << ordinal();
    }

}
