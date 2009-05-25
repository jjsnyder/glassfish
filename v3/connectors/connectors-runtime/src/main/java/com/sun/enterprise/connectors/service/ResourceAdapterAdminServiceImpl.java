/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 * 
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 * 
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 * 
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 * 
 * Contributor(s):
 * 
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.enterprise.connectors.service;

import com.sun.appserv.connectors.internal.api.ConnectorRuntimeException;
import com.sun.appserv.connectors.internal.api.ConnectorsUtil;
import com.sun.enterprise.connectors.ActiveResourceAdapter;
import com.sun.enterprise.connectors.ConnectorRegistry;
import com.sun.enterprise.connectors.ConnectorRuntime;
import com.sun.enterprise.connectors.module.ConnectorApplication;
import com.sun.enterprise.connectors.util.ConnectorDDTransformUtils;
import com.sun.enterprise.connectors.util.ResourcesUtil;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.ConnectorDescriptor;
import com.sun.enterprise.deployment.util.ModuleDescriptor;
import com.sun.enterprise.config.serverbeans.ResourceAdapterConfig;

import javax.naming.NamingException;
import java.util.logging.Level;
import java.util.Set;
import java.util.List;
import java.util.concurrent.*;

import org.glassfish.api.admin.config.Property;
import org.glassfish.internal.api.DelegatingClassLoader;
import org.glassfish.internal.api.ConnectorClassFinder;


/**
 * This is resource adapter admin service. It creates, deletes Resource adapter
 * and also the resource adapter configuration updation.
 *
 * @author Binod P.G, Srikanth P, Aditya Gore, Jagadish Ramu
 */
public class ResourceAdapterAdminServiceImpl extends ConnectorService {

    private ExecutorService execService = Executors.newCachedThreadPool();

    /**
     * Default constructor
     */
    public ResourceAdapterAdminServiceImpl() {
        super();
    }

    /**
     * Destroys/deletes the Active resource adapter object from the connector
     * container. Active resource adapter abstracts the rar deployed.
     *
     * @param moduleName Name of the rarModule to destroy/delete
     * @throws ConnectorRuntimeException if the deletion fails
     */
    private void destroyActiveResourceAdapter(String moduleName) throws ConnectorRuntimeException {

        ResourcesUtil resutil = ResourcesUtil.createInstance();
        if (resutil == null) {
            ConnectorRuntimeException cre =
                    new ConnectorRuntimeException("Failed to get ResourcesUtil object");
            _logger.log(Level.SEVERE, "rardeployment.resourcesutil_get_failure", moduleName);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        }

        if (!stopAndRemoveActiveResourceAdapter(moduleName)) {
            ConnectorRuntimeException cre =
                    new ConnectorRuntimeException("Failed to remove Active Resource Adapter");
            _logger.log(Level.SEVERE, "rardeployment.ra_removal_registry_failure", moduleName);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        }

        try {

            String descriptorJNDIName = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForDescriptor(moduleName);

            _logger.fine("ResourceAdapterAdminServiceImpl :: destroyActiveRA "
                    + moduleName + " removing descriptor " + descriptorJNDIName);

            _runtime.getNamingManager().getInitialContext().unbind(descriptorJNDIName);

        } catch (NamingException ne) {
            ConnectorRuntimeException cre =
                    new ConnectorRuntimeException("Failed to remove connector descriptor from JNDI");
            cre.initCause(ne);
            _logger.log(Level.SEVERE, "rardeployment.connector_descriptor_jndi_removal_failure", moduleName);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        }
    }

    /**
     * Creates Active resource Adapter which abstracts the rar module. During
     * the creation of ActiveResourceAdapter, default pools and resources also
     * are created.
     *
     * @param connectorDescriptor object which abstracts the connector deployment descriptor
     *                            i.e rar.xml and sun-ra.xml.
     * @param moduleName          Name of the module
     * @param moduleDir           Directory where rar module is exploded.
     * @param loader              Classloader to use
     * @throws ConnectorRuntimeException if creation fails.
     */

    public synchronized void createActiveResourceAdapter(ConnectorDescriptor connectorDescriptor,
                                                         String moduleName, String moduleDir, ClassLoader loader)
            throws ConnectorRuntimeException {

        _logger.fine("ResourceAdapterAdminServiceImpl :: createActiveRA "
                + moduleName + " at " + moduleDir);

        ActiveResourceAdapter activeResourceAdapter = _registry.getActiveResourceAdapter(moduleName);
        if (activeResourceAdapter != null) {
            _logger.log(Level.FINE, "rardeployment.resourceadapter.already.started", moduleName);
            return;
        }


        //TODO V3 works fine ?
        if (loader == null) {
            try {
                loader = connectorDescriptor.getClassLoader();
            } catch (Exception ex) {
                _logger.log(Level.FINE, "No classloader available with connector descriptor");
                loader = null;
            }
        }
        ConnectorRuntime connectorRuntime = ConnectorRuntime.getRuntime();
        ModuleDescriptor moduleDescriptor = null;
        Application application = null;
        _logger.fine("ResourceAdapterAdminServiceImpl :: createActiveRA "
                + moduleName + " at " + moduleDir + " loader :: " + loader);
        //class-loader can not be null for standalone rar as deployer should have provided one.
        //class-laoder can (may) be null for system-rars as they are not actually deployed.
        //TODO V3 don't check for system-ra if the resource-adapters are not loaded before recovery
        // (standalone + embedded) 
        if (loader == null && ConnectorsUtil.belongsToSystemRA(moduleName)) {
            if (connectorRuntime.isServer()) {
                loader = connectorRuntime.createConnectorClassLoader(moduleDir, null);
                // create a classloader for system rar and add it to connector class loader
                // this is different than v2 as we dont add system rars to class loader chain
                DelegatingClassLoader ccl = connectorRuntime.getConnectorClassLoader();
                boolean systemRarCLAdded = ccl.addDelegate((ConnectorClassFinder)loader);
                if(_logger.isLoggable(Level.FINE)){
                    _logger.log(Level.FINE, "System RAR [ "+moduleName+" ] added to " +
                        "classloader chain : " + systemRarCLAdded);
                }

                if (loader == null) {
                    ConnectorRuntimeException cre =
                            new ConnectorRuntimeException("Failed to obtain the class loader");
                    _logger.log(Level.SEVERE,"rardeployment.failed_toget_classloader");
                    _logger.log(Level.SEVERE, "", cre);
                    throw cre;
                }
            }
        } else {
            connectorDescriptor.setClassLoader(null);
            moduleDescriptor = connectorDescriptor.getModuleDescriptor();
            application = connectorDescriptor.getApplication();
            connectorDescriptor.setModuleDescriptor(null);
            connectorDescriptor.setApplication(null);
        }
        try {

            activeResourceAdapter =
                    connectorRuntime.getActiveRAFactory().
                            createActiveResourceAdapter(connectorDescriptor, moduleName, loader);
            _logger.fine("ResourceAdapterAdminServiceImpl :: createActiveRA " +
                    moduleName + " at " + moduleDir +
                    " adding to registry " + activeResourceAdapter);
            _registry.addActiveResourceAdapter(moduleName, activeResourceAdapter);
            _logger.fine("ResourceAdapterAdminServiceImpl:: createActiveRA " +
                    moduleName + " at " + moduleDir
                    + " env =server ? " + (connectorRuntime.isServer()));

            if (connectorRuntime.isServer()) {
                activeResourceAdapter.setup();
                String descriptorJNDIName = ConnectorAdminServiceUtils.getReservePrefixedJNDINameForDescriptor(moduleName);
                _logger.fine("ResourceAdapterAdminServiceImpl :: createActiveRA "
                        + moduleName + " at " + moduleDir
                        + " publishing descriptor " + descriptorJNDIName);

                //Update RAConfig in Connector Descriptor and bind in JNDI
                //so that ACC clients could use RAConfig
                updateRAConfigInDescriptor(connectorDescriptor, moduleName);
/*
                ConnectorInternalObjectsProxy proxy = new ConnectorInternalObjectsProxy(connectorDescriptor);
                _runtime.getNamingManager().publishObject(descriptorJNDIName, proxy, true);
*/
                _runtime.getNamingManager().publishObject(descriptorJNDIName, connectorDescriptor, true);

                String securityWarningMessage=
                    connectorRuntime.getSecurityPermissionSpec(moduleName);
                // To i18N.
                if (securityWarningMessage != null) {
                    _logger.log(Level.WARNING, securityWarningMessage);
                }
            }

        } catch (NullPointerException npEx) {
            ConnectorRuntimeException cre =
                    new ConnectorRuntimeException("Error in creating active RAR");
            cre.initCause(npEx);
            _logger.log( Level.SEVERE, "rardeployment.nullPointerException", moduleName);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        } catch (NamingException ne) {
            ConnectorRuntimeException cre =
                    new ConnectorRuntimeException("Error in creating active RAR");
            cre.initCause(ne);
            _logger.log(Level.SEVERE, "rardeployment.jndi_publish_failure");
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        } finally {
            if (moduleDescriptor != null) {
                connectorDescriptor.setModuleDescriptor(moduleDescriptor);
                connectorDescriptor.setApplication(application);
                connectorDescriptor.setClassLoader(loader);
            }
        }
    }

    /*
     * Updates the connector descriptor of the connector module, with the 
     * contents of a resource adapter config if specified.
     * 
     * This modified ConnectorDescriptor is then bound to JNDI so that ACC 
     * clients while configuring a non-system RAR could get the correct merged
     * configuration. Any updates to resource-adapter config while an ACC client
     * is in use is not transmitted to the client dynamically. All such changes 
     * would be visible on ACC client restart. 
     */

    private void updateRAConfigInDescriptor(ConnectorDescriptor connectorDescriptor,
                                            String moduleName) {

        ResourceAdapterConfig raConfig =
                ConnectorRegistry.getInstance().getResourceAdapterConfig(moduleName);

        List<Property> raConfigProps = null;
        if (raConfig != null) {
            raConfigProps = raConfig.getProperty();
        }

        _logger.fine("current RAConfig In Descriptor " + connectorDescriptor.getConfigProperties());

        if (raConfigProps != null) {
            Set mergedProps = ConnectorDDTransformUtils.mergeProps(
                    raConfigProps, connectorDescriptor.getConfigProperties());
            Set actualProps = connectorDescriptor.getConfigProperties();
            actualProps.clear();
            actualProps.addAll(mergedProps);
            _logger.fine("updated RAConfig In Descriptor " + connectorDescriptor.getConfigProperties());
        }

    }


    /**
     * Creates Active resource Adapter which abstracts the rar module. During
     * the creation of ActiveResourceAdapter, default pools and resources also
     * are created.
     *
     * @param moduleDir  Directory where rar module is exploded.
     * @param moduleName Name of the module
     * @throws ConnectorRuntimeException if creation fails.
     */
    public synchronized void createActiveResourceAdapter(String moduleDir, String moduleName, ClassLoader loader)
            throws ConnectorRuntimeException {

        ActiveResourceAdapter activeResourceAdapter =
                _registry.getActiveResourceAdapter(moduleName);
        if (activeResourceAdapter != null) {
            _logger.log(Level.FINE, "rardeployment.resourceadapter.already.started", moduleName);
            return;
        }

        if (ConnectorsUtil.belongsToSystemRA(moduleName)) {
            moduleDir = ConnectorsUtil.getSystemModuleLocation(moduleName);
        }

        ConnectorDescriptor connectorDescriptor = ConnectorDDTransformUtils.getConnectorDescriptor(moduleDir);

        if (connectorDescriptor == null) {
            ConnectorRuntimeException cre = new ConnectorRuntimeException("Failed to obtain the connectorDescriptor");
            _logger.log(Level.SEVERE, "rardeployment.connector_descriptor_notfound", moduleName);
            _logger.log(Level.SEVERE, "", cre);
            throw cre;
        }

        createActiveResourceAdapter(connectorDescriptor, moduleName, moduleDir, loader);
    }



    /**
     * Stops the resourceAdapter and removes it from connector container/
     * registry.
     *
     * @param moduleName Rarmodule name.
     * @return true it is successful stop and removal of ActiveResourceAdapter
     *         false it stop and removal fails.
     */
    private boolean stopAndRemoveActiveResourceAdapter(String moduleName) {

        ActiveResourceAdapter acr = null;
        if (moduleName != null) {
            acr = _registry.getActiveResourceAdapter(moduleName);
        }
        if (acr != null) {
            sendStopToResourceAdapter(acr);

            // remove the system rar from class loader chain.
            if(ConnectorsUtil.belongsToSystemRA(moduleName)) {
                ConnectorClassFinder ccf =
                        (ConnectorClassFinder)ConnectorRegistry.getInstance().
                                getActiveResourceAdapter(moduleName).getClassLoader();
                ConnectorRuntime connectorRuntime = ConnectorRuntime.getRuntime();
                DelegatingClassLoader ccl = connectorRuntime.getConnectorClassLoader();
                boolean systemRarCLRemoved = ccl.removeDelegate(ccf);
                if(_logger.isLoggable(Level.FINE)){
                    _logger.log(Level.FINE, "System RAR [ "+moduleName+" ] removed from " +
                        "classloader chain : " + systemRarCLRemoved);
                }
            }
            return _registry.removeActiveResourceAdapter(moduleName);
        }
        return false;
    }

    /**
     * Checks if the rar module is already reployed.
     *
     * @param moduleName Rarmodule name
     * @return true if it is already deployed. false if it is not deployed.
     */
    public boolean isRarDeployed(String moduleName) {

        ActiveResourceAdapter activeResourceAdapter =
                _registry.getActiveResourceAdapter(moduleName);
        return activeResourceAdapter != null;
    }

    /**
     * Calls the stop method for all J2EE Connector 1.5/1.0 spec compliant RARs
     */
    public void stopAllActiveResourceAdapters() {
        ActiveResourceAdapter[] resourceAdapters =
                ConnectorRegistry.getInstance().getAllActiveResourceAdapters();

        for (ActiveResourceAdapter resourceAdapter : resourceAdapters) {
            stopActiveResourceAdapter(resourceAdapter.getModuleName());
        }
    }

    /**
     * stop the active resource adapter (runtime)
     * @param raName resource-adapter name
     */
    public void stopActiveResourceAdapter(String raName) {
        _logger.log(Level.FINE, "Stopping RA : ", raName);
        try {
            destroyActiveResourceAdapter(raName);
        } catch (ConnectorRuntimeException cre) {
            _logger.log(Level.WARNING, "unable to stop resource adapter [ " + raName + " ]", cre.getMessage());
            _logger.log(Level.FINE, "unable to stop resource adapter [ " + raName + " ]", cre);
        }
    }

    /**
     * add the resource-adapter-config
     * @param rarName resource-adapter name
     * @param raConfig resource-adapter-config
     * @throws ConnectorRuntimeException
     */
    public void addResourceAdapterConfig(String rarName, ResourceAdapterConfig raConfig)
        throws ConnectorRuntimeException {
        if (rarName != null && raConfig != null) {
            _registry.addResourceAdapterConfig(rarName, raConfig);
            reCreateActiveResourceAdapter(rarName);
        }
    }

    /**
	 * Delete the resource adapter configuration to the connector registry
	 * @param rarName resource-adapter-name
     * @throws ConnectorRuntimeException when unable to remove RA Config.
	 */
    public void deleteResourceAdapterConfig(String rarName) throws ConnectorRuntimeException {
        if (rarName != null) {
            _registry.removeResourceAdapterConfig(rarName);
            reCreateActiveResourceAdapter(rarName);
        }
    }

    /**
	 * The ActiveResourceAdapter object which abstract the rar module is
	 * recreated in the connector container/registry. All the pools and
	 * resources are killed. But the infrastructure to create the pools and and
	 * resources is untouched. Only the actual pool is killed.
	 *
	 * @param moduleName
	 *                     rar module Name.
	 * @throws ConnectorRuntimeException
	 *                      if recreation fails.
	 */

    public void reCreateActiveResourceAdapter(String moduleName)
            throws ConnectorRuntimeException {

        String moduleDir = ConnectorsUtil.getLocation(moduleName);
        /* TODO V3 moduleDir=null can happen only for embedded rar,
        need to decide whether it need to be handled or not */
        if (moduleDir != null) {
            //TODO V3 is there a case where RAR is not deployed ?
            if (isRarDeployed(moduleName)) {
                ConnectorApplication app = _registry.getConnectorApplication(moduleName);
                app.undeployResources();
                stopAndRemoveActiveResourceAdapter(moduleName);
                createActiveResourceAdapter(moduleDir, moduleName, app.getClassLoader());
                _registry.getConnectorApplication(moduleName).deployResources();
            }
            //No need to deploy the .rar, it may be a case where rar is not deployed yet
            //Also, when the rar is started, RA-Config is anyway used
            /*else {
                ConnectorApplication app = _registry.getConnectorApplication(moduleName);
                createActiveResourceAdapter(moduleDir, moduleName, app.getClassLoader());
                _registry.getConnectorApplication(moduleName).deployResources();
            }*/
        }
    }

    /**
     * Calls the stop method for all RARs
     *
     * @param resourceAdapterToStop ra to stop
     */
    private void sendStopToResourceAdapter(ActiveResourceAdapter
            resourceAdapterToStop) {

        Runnable rast = new RAShutdownTask(resourceAdapterToStop);
        String raName =  resourceAdapterToStop.getModuleName();

        Long timeout = ConnectorRuntime.getRuntime().getShutdownTimeout();

        Future future = null;
        boolean stopSuccessful = false;
        try {
            _logger.log(Level.FINE, "scheduling stop for RA [ " + raName +" ] ");
            future = execService.submit(rast);
            future.get(timeout, TimeUnit.MILLISECONDS);
            _logger.log(Level.FINE, "stop() Complete for active 1.5 compliant RAR " +
                    "[ "+ raName  +" ]");
            stopSuccessful = true;
        } catch (TimeoutException e) {
            e.printStackTrace();
            cancelTask(future, true, raName);
        } catch(Exception e){
            e.printStackTrace();
            cancelTask(future, true, raName);
        }

        if (stopSuccessful) {
            _logger.log(Level.INFO, "ra.stop-successful", raName);
        } else {
            _logger.log(Level.WARNING, "ra.stop-unsuccessful", raName);
        }
    }

    private void cancelTask(Future future, boolean interruptIfRunning, String raName){
        if(future != null){
            if(!(future.isCancelled()) && !(future.isDone())){
                boolean cancelled = future.cancel(interruptIfRunning);
                _logger.log(Level.INFO, "cancelling the shutdown of RA [ " + raName +" ] status : " + cancelled);
            } else {
                _logger.log(Level.INFO, "shutdown of RA [ " + raName +" ] is either already complete or already cancelled");
            }
        }
    }

    private class RAShutdownTask implements Runnable {
        private ActiveResourceAdapter ra;

        public RAShutdownTask(ActiveResourceAdapter ratoBeShutDown) {
            super();
            this.ra = ratoBeShutDown;
            //This thread is a daemon threadS
            // TODO V3 not needed anymore as we use ExecService with timeout
            // this.setDaemon(true);
        }

        public void run() {
            _logger.log(Level.FINE, "Calling RA [ " + ra.getModuleName() + " ] shutdown ");
            this.ra.destroy();
        }
    }
}
