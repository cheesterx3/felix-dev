package dm.impl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import dm.ServiceDependency;

/**
 * Temporal Service dependency implementation, used to hide temporary service dependency "outage".
 * Only works with a required dependency.
 *
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class TemporalServiceDependencyImpl extends ServiceDependencyImpl implements ServiceDependency, InvocationHandler {
    // Max millis to wait for service availability.
    private final long m_timeout;
        
    // Framework bundle (we use it to detect if the framework is stopping)
    private final Bundle m_frameworkBundle;

    // The service proxy, which blocks when service is not available.
    private volatile Object m_serviceInstance;

    /**
     * Creates a new Temporal Service Dependency.
     * 
     * @param context The bundle context of the bundle which is instantiating this dependency object
     * @param logger the logger our Internal logger for logging events.
     * @see DependencyActivatorBase#createTemporalServiceDependency()
     */
    public TemporalServiceDependencyImpl(BundleContext context, Logger logger, long timeout) {
        super(context, logger);
        super.setRequired(true);
        if (timeout < 0) {
            throw new IllegalArgumentException("Invalid timeout value: " + timeout);
        }
        m_timeout = timeout;
        m_frameworkBundle = context.getBundle(0);
    }

    /**
     * Sets the required flag which determines if this service is required or not. This method
     * just override the superclass method in order to check if the required flag is true 
     * (optional dependency is not supported by this class).
     * 
     * @param required the required flag, which must be set to true
     * @return this service dependency
     * @throws IllegalArgumentException if the "required" parameter is not true.
     */
    @Override
    public ServiceDependency setRequired(boolean required) {
        if (! required) {
            throw new IllegalArgumentException("A Temporal Service dependency can't be optional");
        }
        super.setRequired(required);
        return this;
    }
    
    /**
     * The ServiceTracker calls us here in order to inform about a service arrival.
     */
    @Override
    public void addedService(ServiceReference ref, Object service) {
        // Update our service cache, using the tracker. We do this because the
        // just added service might not be the service with the highest rank ...
        boolean makeAvailable = false;
        synchronized (this) {
            if (m_serviceInstance == null) {
                m_serviceInstance = Proxy.newProxyInstance(m_trackedServiceName.getClassLoader(), new Class[] { m_trackedServiceName }, this);
                makeAvailable = true;
            }
        }
        if (makeAvailable) {
            add(new ServiceEventImpl(ref, m_serviceInstance));
        } else {
            // This added will possibly unblock our invoke() method (if it's blocked in m_tracker.waitForService method).
        }
    }

    /**
     * The ServiceTracker calls us here when a tracked service properties are modified.
     */
    @Override
    public void modifiedService(ServiceReference ref, Object service) {
        // We don't care.
    }

    /**
     * The ServiceTracker calls us here when a tracked service is lost.
     */
    @Override
    public void removedService(ServiceReference ref, Object service) {
        // If we detect that the fwk is stopping, we behave as our superclass. That is:
        // the lost dependency has to trigger our service deactivation, since the fwk is stopping
        // and the lost dependency won't come up anymore.
        if (m_frameworkBundle.getState() == Bundle.STOPPING) {
            // Important: Notice that calling "super.removedService() might invoke our service "stop" 
            // callback, which in turn might invoke the just removed service dependency. In this case, 
            // our "invoke" method won't use the tracker to get the service dependency (because at this point, 
            // the tracker has withdrawn its reference to the lost service). So, you will see that the "invoke" 
            // method will use the "m_cachedService" instead ...
            boolean makeUnavailable = false;
            synchronized (this) {
                if (m_tracker.getService() == null) {
                    makeUnavailable = true;
                }
            }
            if (makeUnavailable) {
                remove(new ServiceEventImpl(ref, m_serviceInstance)); // will unget the service ref
            }
        } else {
            // Unget what we got in addingService (see ServiceTracker 701.4.1)
            m_context.ungetService(ref);
            // if there is no available services, the next call to invoke() method will block until another service
            // becomes available. Else the next call to invoke() will return that highest ranked available service.
        }
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        Object service = null;
        try {
            service = m_tracker.waitForService(m_timeout);
        } catch (InterruptedException e) {            
        }
        
        if (service == null) {
            throw new IllegalStateException("Service unavailable: " + m_trackedServiceName.getName());
        }
        
        try {
            return method.invoke(service, args);
        }
        catch (IllegalAccessException iae) {
            method.setAccessible(true);
            return method.invoke(service, args);
        }
    }
}
