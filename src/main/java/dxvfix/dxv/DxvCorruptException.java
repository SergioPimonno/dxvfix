package dxvfix.dxv;

/** Signals a structural violation found while replaying the DXV bitstream state machine. */
final class DxvCorruptException extends RuntimeException {
    DxvCorruptException(String message) {
        super(message);
    }
}
