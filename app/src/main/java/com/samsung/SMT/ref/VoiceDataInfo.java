package com.samsung.SMT.ref;

import java.io.File;
import java.util.Locale;

// The native engines read these fields by name; keep the package, class and field names.
public class VoiceDataInfo {

    public enum eFileType {
        UNKNOWN, DT, QS, AM, CFG, LNG, CART, PB, IVC, SKIPS, BIN, CACHE, JSON, LNGS, CFGS, WAV, F32,
        CFG_HMM, CFG_NN_UC
    }

    private eFileType eType = eFileType.UNKNOWN;
    private int fd = -1;
    private String filename;
    private long length;
    private long offset;
    private int type;
    private int version;

    public void setFDInfo(int fd, long offset, long length) {
        this.fd = fd;
        this.offset = offset;
        this.length = length;
    }

    public void setFileInfo(String filename) {
        this.filename = filename;
        this.eType = parseFileType(filename);
        this.type = eType.ordinal();
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public int getFD() {
        return fd;
    }

    public String getFileName() {
        return filename;
    }

    public eFileType getType() {
        return eType;
    }

    public static eFileType parseFileType(String path) {
        String name = new File(path).getName().toLowerCase(Locale.ROOT);
        for (eFileType t : new eFileType[]{eFileType.DT, eFileType.QS, eFileType.AM, eFileType.CART, eFileType.PB}) {
            if (name.endsWith(t.name().toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        if (name.matches("^[0-9]{2}.lng$")) {
            return eFileType.LNGS;
        }
        if (name.endsWith("lng")) {
            return eFileType.LNG;
        }
        for (eFileType t : new eFileType[]{eFileType.BIN, eFileType.IVC, eFileType.SKIPS}) {
            if (name.endsWith(t.name().toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        if (name.startsWith("cache")) {
            return eFileType.CACHE;
        }
        for (eFileType t : new eFileType[]{eFileType.JSON, eFileType.WAV, eFileType.F32}) {
            if (name.endsWith(t.name().toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        if (name.matches("^[0-9]{2}.cfg$")) {
            return eFileType.CFGS;
        }
        return name.endsWith("cfg") ? eFileType.CFG : eFileType.UNKNOWN;
    }

    @Override
    public String toString() {
        return filename + " [" + eType + " fd=" + fd + " off=" + offset + " len=" + length + "]";
    }
}
