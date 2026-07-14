package dxvfix.mp4;

public enum CodecKind {
    DXV, PRORES, H264, H265, NOTCHLC, UNKNOWN;

    public static CodecKind fromFourCC(String fourcc) {
        if (fourcc == null) return UNKNOWN;
        switch (fourcc) {
            case "DXD0": case "DXD1": case "DXD3": case "DXDI": case "DXDN":
            case "DXTC": case "DXY0": case "DXTA": case "DXT5": case "DXT3": case "DXT1":
                return DXV;
            case "apch": case "apcn": case "apcs": case "apco": case "ap4h": case "ap4x":
                return PRORES;
            case "avc1": case "avc3":
                return H264;
            case "hvc1": case "hev1":
                return H265;
            case "nclc":
                return NOTCHLC;
            default:
                // DXV assets are sometimes tagged with other DXD-prefixed fourccs too.
                return fourcc.startsWith("DXD") ? DXV : UNKNOWN;
        }
    }

    public String label() {
        switch (this) {
            case DXV: return "DXV (Resolume)";
            case PRORES: return "Apple ProRes";
            case H264: return "H.264/AVC";
            case H265: return "H.265/HEVC";
            case NOTCHLC: return "NotchLC";
            default: return "Unknown";
        }
    }
}
