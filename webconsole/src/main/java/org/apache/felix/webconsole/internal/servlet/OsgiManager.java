/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.felix.webconsole.internal.servlet;


import java.io.IOException;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.GenericServlet;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.webconsole.AbstractWebConsolePlugin;
import org.apache.felix.webconsole.Action;
import org.apache.felix.webconsole.Render;
import org.apache.felix.webconsole.WebConsoleConstants;
import org.apache.felix.webconsole.internal.Logger;
import org.apache.felix.webconsole.internal.OsgiManagerPlugin;
import org.apache.felix.webconsole.internal.Util;
import org.apache.felix.webconsole.internal.core.BundlesServlet;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.http.HttpContext;
import org.osgi.service.http.HttpService;
import org.osgi.service.log.LogService;
import org.osgi.util.tracker.ServiceTracker;


/**
 * The <code>OSGi Manager</code> TODO
 *
 * @scr.component ds="no" label="%manager.name"
 *                description="%manager.description"
 */
public class OsgiManager extends GenericServlet
{

    /** Pseudo class version ID to keep the IDE quite. */
    private static final long serialVersionUID = 1L;

    public static final String ATTR_LABEL_MAP = OsgiManager.class.getName() + ".labelMap";

    public static final String ATTR_APP_ROOT = OsgiManager.class.getName() + ".appRoot";

    /**
     * The name and value of a parameter which will prevent redirection to a
     * render after the action has been executed (value is "_noredir_"). This
     * may be used by programmatic action submissions.
     */
    public static final String PARAM_NO_REDIRECT_AFTER_ACTION = "_noredir_";

    /**
     * @scr.property valueRef="DEFAULT_MANAGER_ROOT"
     */
    private static final String PROP_MANAGER_ROOT = "manager.root";

    /**
     * @scr.property valueRef="DEFAULT_PAGE"
     */
    private static final String PROP_DEFAULT_RENDER = "default.render";

    /**
     * @scr.property valueRef="DEFAULT_REALM"
     */
    private static final String PROP_REALM = "realm";

    /**
     * @scr.property valueRef="DEFAULT_USER_NAME"
     */
    private static final String PROP_USER_NAME = "username";

    /**
     * @scr.property valueRef="DEFAULT_PASSWORD"
     */
    private static final String PROP_PASSWORD = "password";

    private static final String DEFAULT_PAGE = BundlesServlet.NAME;

    private static final String DEFAULT_REALM = "OSGi Management Console";

    private static final String DEFAULT_USER_NAME = "admin";

    private static final String DEFAULT_PASSWORD = "admin";

    /**
     * The default value for the {@link #PROP_MANAGER_ROOT} configuration
     * property (value is "/system/console").
     */
    private static final String DEFAULT_MANAGER_ROOT = "/system/console";

    private static final String[] PLUGIN_CLASSES =
        { "org.apache.felix.webconsole.internal.compendium.ComponentConfigurationPrinter",
            "org.apache.felix.webconsole.internal.compendium.ComponentsServlet",
            "org.apache.felix.webconsole.internal.compendium.ConfigManager",
            "org.apache.felix.webconsole.internal.compendium.LogServlet",
            "org.apache.felix.webconsole.internal.core.BundlesServlet",
            "org.apache.felix.webconsole.internal.core.InstallAction",
            "org.apache.felix.webconsole.internal.core.SetStartLevelAction",
            "org.apache.felix.webconsole.internal.deppack.DepPackServlet",
            "org.apache.felix.webconsole.internal.misc.EventAdminServlet",
            "org.apache.felix.webconsole.internal.misc.LicenseServlet",
            "org.apache.felix.webconsole.internal.misc.ConfigurationRender",
            "org.apache.felix.webconsole.internal.misc.ShellServlet",
            "org.apache.felix.webconsole.internal.obr.BundleRepositoryRender",
            "org.apache.felix.webconsole.internal.obr.InstallFromRepoAction",
            "org.apache.felix.webconsole.internal.obr.RefreshRepoAction",
            "org.apache.felix.webconsole.internal.system.GCAction",
            "org.apache.felix.webconsole.internal.system.VMStatPlugin" };

    private BundleContext bundleContext;

    private Logger log;

    private ServiceTracker httpServiceTracker;

    private HttpService httpService;

    private ServiceTracker operationsTracker;

    private ServiceTracker rendersTracker;

    private ServiceTracker pluginsTracker;

    private ServiceRegistration configurationListener;

    private Map plugins = new HashMap();

    private Map labelMap = new HashMap();

    private Map operations = new HashMap();

    private Servlet defaultRender;

    private String defaultRenderName;

    private String webManagerRoot;

    // true if the OsgiManager is registered as a Servlet with the HttpService
    private boolean httpServletRegistered;

    // true if the resources have been registered with the HttpService
    private boolean httpResourcesRegistered;

    private Dictionary configuration;


    public OsgiManager( BundleContext bundleContext )
    {

        this.bundleContext = bundleContext;
        this.log = new Logger( bundleContext );

        updateConfiguration( null );

        try
        {
            this.configurationListener = ConfigurationListener.create( this );
        }
        catch ( Throwable t )
        {
            // might be caused by CM not available
        }

        // get at the HttpService first, this should initialize
        // the OSGi Manager and start the initial setup
        httpServiceTracker = new HttpServiceTracker( this );
        httpServiceTracker.open();
    }

    
    public void dispose()
    {
        // now drop the HttpService and continue with further destroyals
        if ( httpServiceTracker != null )
        {
            httpServiceTracker.close();
            httpServiceTracker = null;
        }

        // stop listening for configuration
        if ( configurationListener != null )
        {
            configurationListener.unregister();
            configurationListener = null;
        }

        if ( log != null )
        {
            log.dispose();
        }

        this.defaultRender = null;
        this.bundleContext = null;
    }


    //---------- Servlet API
    
    public void init()
    {
        // base class initialization not needed, since the GenericServlet.init
        // is an empty method

        // setup the included plugins
        ClassLoader classLoader = getClass().getClassLoader();
        for ( int i = 0; i < PLUGIN_CLASSES.length; i++ )
        {
            String pluginClassName = PLUGIN_CLASSES[i];
            try
            {
                Class pluginClass = classLoader.loadClass( pluginClassName );
                Object plugin = pluginClass.newInstance();
                if ( plugin instanceof OsgiManagerPlugin )
                {
                    ( ( OsgiManagerPlugin ) plugin ).activate( bundleContext );
                }
                if ( plugin instanceof AbstractWebConsolePlugin )
                {
                    AbstractWebConsolePlugin amp = ( AbstractWebConsolePlugin ) plugin;
                    bindServlet( amp.getLabel(), amp );
                }
                else
                {
                    if ( plugin instanceof Action )
                    {
                        bindOperation( ( Action ) plugin );
                    }
                    if ( plugin instanceof Render )
                    {
                        bindRender( ( Render ) plugin );
                    }
                }
            }
            catch ( Throwable t )
            {
                log.log( LogService.LOG_INFO, "Failed to instantiate plugin " + pluginClassName + ". Reason: " + t );
            }
        }

        // start tracking external plugins after setting up our own plugins
        operationsTracker = new OperationServiceTracker( this );
        operationsTracker.open();
        rendersTracker = new RenderServiceTracker( this );
        rendersTracker.open();
        pluginsTracker = new PluginServiceTracker( this );
        pluginsTracker.open();
    }
    
    public void service( ServletRequest req, ServletResponse res ) throws ServletException, IOException
    {

        HttpServletRequest request = ( HttpServletRequest ) req;
        HttpServletResponse response = ( HttpServletResponse ) res;

        // handle the request action, terminate if done
        if ( this.handleAction( request, response ) )
        {
            return;
        }

        // check whether we are not at .../{webManagerRoot}
        if ( request.getPathInfo() == null || request.getPathInfo().equals( "/" ) )
        {
            String path = request.getRequestURI();
            if ( !path.endsWith( "/" ) )
            {
                path = path.concat( "/" );
            }
            path = path.concat( defaultRenderName );
            response.sendRedirect( path );
            return;
        }

        String label = request.getPathInfo();
        int slash = label.indexOf( "/", 1 );
        if ( slash < 2 )
        {
            slash = label.length();
        }

        label = label.substring( 1, slash );
        Servlet plugin = ( Servlet ) plugins.get( label );
        if ( plugin != null )
        {
            req.setAttribute( ATTR_LABEL_MAP, labelMap );
            req.setAttribute( ATTR_APP_ROOT, request.getContextPath() + request.getServletPath() );

            plugin.service( req, res );
        }
        else
        {
            response.sendError( HttpServletResponse.SC_NOT_FOUND );
            return;
        }

    }
    
    public void destroy()
    {
        // base class destroy not needed, since the GenericServlet.destroy
        // is an empty method
        
        // stop listening for plugins
        if ( operationsTracker != null )
        {
            operationsTracker.close();
            operationsTracker = null;
        }
        if ( rendersTracker != null )
        {
            rendersTracker.close();
            rendersTracker = null;
        }
        if ( pluginsTracker != null )
        {
            pluginsTracker.close();
            pluginsTracker = null;
        }

        // deactivate any remaining plugins
        for ( Iterator pi = plugins.values().iterator(); pi.hasNext(); )
        {
            Object plugin = pi.next();
            if ( plugin instanceof OsgiManagerPlugin )
            {
                ( ( OsgiManagerPlugin ) plugin ).deactivate();
            }
        }

        // simply remove all operations, we should not be used anymore
        this.plugins.clear();
        this.labelMap.clear();
        this.operations.clear();
    }

    //---------- internal

    protected boolean handleAction( HttpServletRequest req, HttpServletResponse resp ) throws IOException, ServletException
    {
        // check action
        String actionName = AbstractWebConsolePlugin.getParameter( req, Util.PARAM_ACTION );
        if ( actionName != null )
        {
            Action action = ( Action ) this.operations.get( actionName );
            if ( action != null )
            {
                boolean redirect = true;
                try
                {
                    redirect = action.performAction( req, resp );
                }
                catch ( IOException ioe )
                {
                    log.log( LogService.LOG_WARNING, ioe.getMessage(), ioe );
                    throw ioe;
                }
                catch ( ServletException se )
                {
                    log.log( LogService.LOG_WARNING, se.getMessage(), se.getRootCause() );
                    throw se;
                }

                // maybe overwrite redirect
                if ( PARAM_NO_REDIRECT_AFTER_ACTION.equals( AbstractWebConsolePlugin.getParameter( req,
                    PARAM_NO_REDIRECT_AFTER_ACTION ) ) )
                {
                    resp.setStatus( HttpServletResponse.SC_OK );
                    resp.setContentType( "text/html" );
                    resp.getWriter().println( "Ok" );
                    return true;
                }

                if ( redirect )
                {
                    String uri = req.getRequestURI();
                    // Object pars =
                    // req.getAttribute(Action.ATTR_REDIRECT_PARAMETERS);
                    // if (pars instanceof String) {
                    // uri += "?" + pars;
                    // }
                    resp.sendRedirect( uri );
                }
                return true;
            }
        }

        return false;
    }


    BundleContext getBundleContext()
    {
        return bundleContext;
    }

    private static class HttpServiceTracker extends ServiceTracker
    {

        private final OsgiManager osgiManager;


        HttpServiceTracker( OsgiManager osgiManager )
        {
            super( osgiManager.getBundleContext(), HttpService.class.getName(), null );
            this.osgiManager = osgiManager;
        }


        public Object addingService( ServiceReference reference )
        {
            Object service = super.addingService( reference );
            if ( service instanceof HttpService )
            {
                osgiManager.bindHttpService( ( HttpService ) service );
            }
            return service;
        }


        public void removedService( ServiceReference reference, Object service )
        {
            if ( service instanceof HttpService )
            {
                osgiManager.unbindHttpService( ( HttpService ) service );
            }

            super.removedService( reference, service );
        }
    }

    private static class OperationServiceTracker extends ServiceTracker
    {

        private final OsgiManager osgiManager;


        OperationServiceTracker( OsgiManager osgiManager )
        {
            super( osgiManager.getBundleContext(), Action.SERVICE, null );
            this.osgiManager = osgiManager;
        }


        public Object addingService( ServiceReference reference )
        {
            Object operation = super.addingService( reference );
            if ( operation instanceof Action )
            {
                osgiManager.bindOperation( ( Action ) operation );
            }
            return operation;
        }


        public void removedService( ServiceReference reference, Object service )
        {
            if ( service instanceof Action )
            {
                osgiManager.bindOperation( ( Action ) service );
            }

            super.removedService( reference, service );
        }
    }

    private static class RenderServiceTracker extends ServiceTracker
    {

        private final OsgiManager osgiManager;


        RenderServiceTracker( OsgiManager osgiManager )
        {
            super( osgiManager.getBundleContext(), Render.SERVICE, null );
            this.osgiManager = osgiManager;
        }


        public Object addingService( ServiceReference reference )
        {
            Object operation = super.addingService( reference );
            if ( operation instanceof Render )
            {
                osgiManager.bindRender( ( Render ) operation );
            }
            return operation;
        }


        public void removedService( ServiceReference reference, Object service )
        {
            if ( service instanceof Render )
            {
                osgiManager.bindRender( ( Render ) service );
            }

            super.removedService( reference, service );
        }
    }

    private static class PluginServiceTracker extends ServiceTracker
    {

        private final OsgiManager osgiManager;


        PluginServiceTracker( OsgiManager osgiManager )
        {
            super( osgiManager.getBundleContext(), WebConsoleConstants.SERVICE_NAME, null );
            this.osgiManager = osgiManager;
        }


        public Object addingService( ServiceReference reference )
        {
            Object label = reference.getProperty( WebConsoleConstants.PLUGIN_LABEL );
            if ( label instanceof String )
            {
                Object operation = super.addingService( reference );
                if ( operation instanceof Servlet )
                {
                    // TODO: check reference properties !!
                    osgiManager.bindServlet( ( String ) label, ( Servlet ) operation );
                }
                return operation;
            }

            return null;
        }


        public void removedService( ServiceReference reference, Object service )
        {
            Object label = reference.getProperty( WebConsoleConstants.PLUGIN_LABEL );
            if ( label instanceof String )
            {
                // TODO: check reference properties !!
                osgiManager.unbindServlet( ( String ) label );
            }

            super.removedService( reference, service );
        }
    }


    protected synchronized void bindHttpService( HttpService httpService )
    {
        // do not bind service, when we are already bound
        if ( this.httpService != null )
        {
            log.log( LogService.LOG_DEBUG,
                "bindHttpService: Already bound to an HTTP Service, ignoring further services" );
            return;
        }
        
        Dictionary config = getConfiguration();

        // get authentication details
        String realm = this.getProperty( config, PROP_REALM, DEFAULT_REALM );
        String userId = this.getProperty( config, PROP_USER_NAME, DEFAULT_USER_NAME );
        String password = this.getProperty( config, PROP_PASSWORD, DEFAULT_PASSWORD );

        // register the servlet and resources
        try
        {
            HttpContext httpContext = new OsgiManagerHttpContext( httpService, realm, userId, password );

            Dictionary servletConfig = toStringConfig( config );

            // register this servlet and take note of this
            httpService.registerServlet( this.webManagerRoot, this, servletConfig, httpContext );
            httpServletRegistered = true;

            // register resources and take of this
            httpService.registerResources( this.webManagerRoot + "/res", "/res", httpContext );
            httpResourcesRegistered = true;

        }
        catch ( Exception e )
        {
            log.log( LogService.LOG_ERROR, "bindHttpService: Problem setting up", e );
        }

        this.httpService = httpService;
    }


    protected synchronized void unbindHttpService( HttpService httpService )
    {
        if ( this.httpService != httpService )
        {
            log.log( LogService.LOG_DEBUG,
                "unbindHttpService: Ignoring unbind of an HttpService to which we are not registered" );
            return;
        }

        // drop the service reference
        this.httpService = null;

        if ( httpResourcesRegistered )
        {
            try
            {
                httpService.unregister( this.webManagerRoot + "/res" );
            }
            catch ( Throwable t )
            {
                log.log( LogService.LOG_WARNING, "unbindHttpService: Failed unregistering Resources", t );
            }
            httpResourcesRegistered = false;
        }

        if ( httpServletRegistered )
        {
            try
            {
                httpService.unregister( this.webManagerRoot );
            }
            catch ( Throwable t )
            {
                log.log( LogService.LOG_WARNING, "unbindHttpService: Failed unregistering Servlet", t );
            }
            httpServletRegistered = false;
        }
    }


    protected void bindServlet( String label, Servlet servlet )
    {
        try
        {
            servlet.init( getServletConfig() );
            plugins.put( label, servlet );

            if ( servlet instanceof GenericServlet )
            {
                String title = ( ( GenericServlet ) servlet ).getServletName();
                if ( title != null )
                {
                    labelMap.put( label, title );
                }
            }

            if ( this.defaultRender == null )
            {
                this.defaultRender = servlet;
            }
            else if ( label.equals( this.defaultRenderName ) )
            {
                this.defaultRender = servlet;
            }
        }
        catch ( ServletException se )
        {
            // TODO: log
        }
    }


    protected void unbindServlet( String label )
    {
        Servlet servlet = ( Servlet ) plugins.remove( label );
        if ( servlet != null )
        {
            labelMap.remove( label );

            if ( this.defaultRender == servlet )
            {
                if ( this.plugins.isEmpty() )
                {
                    this.defaultRender = null;
                }
                else
                {
                    this.defaultRender = ( Servlet ) plugins.values().iterator().next();
                }
            }

            servlet.destroy();
        }
    }


    protected void bindOperation( Action operation )
    {
        operations.put( operation.getName(), operation );
    }


    protected void unbindOperation( Action operation )
    {
        operations.remove( operation.getName() );
    }


    protected void bindRender( Render render )
    {
        RenderBridge bridge = new RenderBridge( render );
        bridge.activate( getBundleContext() );
        bindServlet( render.getName(), bridge );
    }


    protected void unbindRender( Render render )
    {
        unbindServlet( render.getName() );
    }


    private Dictionary getConfiguration()
    {
        return configuration;
    }


    void updateConfiguration( Dictionary config )
    {
        if ( config == null )
        {
            config = new Hashtable();
        }

        configuration = config;

        defaultRenderName = getProperty( config, PROP_DEFAULT_RENDER, DEFAULT_PAGE );
        if ( defaultRenderName != null && plugins.get( defaultRenderName ) != null )
        {
            defaultRender = ( Servlet ) plugins.get( defaultRenderName );
        }

        // get the web manager root path
        webManagerRoot = this.getProperty( config, PROP_MANAGER_ROOT, DEFAULT_MANAGER_ROOT );
        if ( !webManagerRoot.startsWith( "/" ) )
        {
            webManagerRoot = "/" + webManagerRoot;
        }

        // might update http service registration
        HttpService httpService = this.httpService;
        if ( httpService != null )
        {
            synchronized ( this )
            {
                unbindHttpService( httpService );
                bindHttpService( httpService );
            }
        }
    }


    /**
     * Returns the named property from the configuration. If the property does
     * not exist, the default value <code>def</code> is returned.
     *
     * @param config The properties from which to returned the named one
     * @param name The name of the property to return
     * @param def The default value if the named property does not exist
     * @return The value of the named property as a string or <code>def</code>
     *         if the property does not exist
     */
    private String getProperty( Dictionary config, String name, String def )
    {
        Object value = config.get( name );
        if ( value instanceof String )
        {
            return ( String ) value;
        }

        if ( value == null )
        {
            return def;
        }

        return String.valueOf( value );
    }


    private Dictionary toStringConfig( Dictionary config )
    {
        Dictionary stringConfig = new Hashtable();
        for ( Enumeration ke = config.keys(); ke.hasMoreElements(); )
        {
            Object key = ke.nextElement();
            stringConfig.put( key.toString(), String.valueOf( config.get( key ) ) );
        }
        return stringConfig;
    }
}
