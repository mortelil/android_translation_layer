// SPDX-License-Identifier: GPL-3.0-only
package android.os;
public class ServiceSpecificException extends RuntimeException {
    public final int errorCode;
    public ServiceSpecificException(int errorCode) { this(errorCode, null); }
    public ServiceSpecificException(int errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
