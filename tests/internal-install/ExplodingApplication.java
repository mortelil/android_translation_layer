package org.atl.internalinstall;
public final class ExplodingApplication extends android.app.Application {
    public ExplodingApplication() { throw new IllegalStateException("INSTALL_MUST_NOT_CONSTRUCT_APPLICATION"); }
    @Override public void onCreate() { throw new IllegalStateException("INSTALL_MUST_NOT_CALL_ONCREATE"); }
}
