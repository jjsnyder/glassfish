/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
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

package com.sun.enterprise.deployment;

import java.util.Properties;
import org.glassfish.deployment.common.Descriptor;

import com.sun.enterprise.deployment.util.DOLUtils;

/**
 * @author Dapeng Hu
 */
public class ConnectorResourceDefinitionDescriptor extends Descriptor {
    private static final long serialVersionUID = 9173518958930316558L;

    // the <description> element will be processed by base class
    private String name ;
    private String className;
    private Properties properties = new Properties();
    
    private String resourceId;
    private MetadataSource metadataSource = MetadataSource.XML;
    private boolean deployed = false;
    private static final String JAVA_URL = "java:";
    private static final String JAVA_COMP_URL = "java:comp/";
    
	public ConnectorResourceDefinitionDescriptor() {
		super();
	}
	
    public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getClassName() {
		return className;
	}


	public void setClassName(String className) {
		this.className = className;
	}

	public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public MetadataSource getMetadataSource() {
		return metadataSource;
	}


	public void setMetadataSource(MetadataSource metadataSource) {
		this.metadataSource = metadataSource;
	}


	public boolean isDeployed() {
		return deployed;
	}


	public void setDeployed(boolean deployed) {
		this.deployed = deployed;
	}


	public void addProperty(String key, String value){
        properties.put(key, value);
    }
    public String getProperty(String key){
        return (String)properties.get(key);
    }

    public Properties getProperties(){
        return properties;
    }

    public boolean equals(Object object) {
        if (object instanceof ConnectorResourceDefinitionDescriptor) {
        	ConnectorResourceDefinitionDescriptor reference = (ConnectorResourceDefinitionDescriptor) object;
            return getJavaName(this.getName()).equals(getJavaName(reference.getName()));
        }
        return false;
    }

    public int hashCode() {
        int result = 17;
        result = 37*result + getName().hashCode();
        return result;
    }
    
    public static String getJavaName(String theName) {
        if(!theName.contains(JAVA_URL)){
        	theName = JAVA_COMP_URL + theName;
        }
        return theName;
    }

    public void addConnectorResourcePropertyDescriptor(ConnectorResourcePropertyDescriptor propertyDescriptor){
        properties.put(propertyDescriptor.getName(), propertyDescriptor.getValue());
    }

    boolean isConflict(ConnectorResourceDefinitionDescriptor other) {
        return (getName().equals(other.getName())) &&
            !(
                DOLUtils.equals(getClassName(), other.getClassName()) &&
                properties.equals(other.properties)
            );
    }

}