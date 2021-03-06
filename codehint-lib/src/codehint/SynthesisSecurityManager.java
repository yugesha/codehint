package codehint;

import java.security.Permission;

class SynthesisSecurityManager extends SecurityManager {
	
	private static class SynthesisSecurityException extends SecurityException {
	    
		private static final long serialVersionUID = 7795599073766113024L;

		@Override
		public Throwable fillInStackTrace() {
	    	return this;
	    }
		
	}

	private final SecurityManager oldSecurityManager;
	@SuppressWarnings("unused")
	private static final Class<CodeHintImpl> codeHintImplClass = CodeHintImpl.class;
	private boolean disabled;
	
	public SynthesisSecurityManager() {
		disabled = true;
		this.oldSecurityManager = System.getSecurityManager();
	}
	
	protected void setEnabled() {
		disabled = false;
	}
	
	protected void disable() {
		boolean wasDisabled = disabled;
		disabled = true;  // Disable before setting the SecurityManager so we don't fail our own check.
		if (!wasDisabled)  // Only reset to the old SecurityManager if we actually set to this one.
			System.setSecurityManager(oldSecurityManager);
	}
	
    @Override
	public void checkPermission(Permission perm) {
    	if ("setSecurityManager".equals(perm.getName()) && !disabled)
	    	throw new SynthesisSecurityException();
    	if (oldSecurityManager != null)
    		oldSecurityManager.checkPermission(perm);
    	// Do nothing and hence allow anything not explicitly disallowed.
    }

    @Override
	public void checkWrite(String file) {
    	throw new SynthesisSecurityException();
    }

    @Override
	public void checkDelete(String file) {
    	throw new SynthesisSecurityException();
    }

    @Override
    public void checkExec(String cmd) {
    	throw new SynthesisSecurityException();
    }

    @Override
    public void checkPrintJobAccess() {
    	throw new SynthesisSecurityException();
    }
    
    @Override
	public void checkConnect(String host, int port) {
    	throw new SynthesisSecurityException();
    }
    
    @Override
	public void checkListen(int port) {
    	throw new SynthesisSecurityException();
    }
    
    /*@Override
    public void checkMemberAccess(Class<?> clazz, int which) {
    	// Our Side Effect handler needs this to be called to handle reflection, since it cannot execute methods within an evaluation and without this the name is not cached.
    	// Note that this only works in JDK <= 7.  In JDK8 this method is deprecated and never called.
    	clazz.getName();
    }*/

    /*
    // Users can do dangerous things with Unsafe, but they need to get it through reflection, so block that.
    @Override
    public void checkMemberAccess(Class<?> clazz, int which) {
    	if (clazz.getName().equals("sun.misc.Unsafe"))
    		throw new SynthesisSecurityException();
    }*/
}
