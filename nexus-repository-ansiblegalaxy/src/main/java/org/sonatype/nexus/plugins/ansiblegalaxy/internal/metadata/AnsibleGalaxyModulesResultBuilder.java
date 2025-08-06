/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2008-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.plugins.ansiblegalaxy.internal.metadata;

import org.sonatype.nexus.plugins.ansiblegalaxy.internal.util.AnsibleGalaxyPathUtils;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

@Named
@Singleton
public class AnsibleGalaxyModulesResultBuilder {
    private final AnsibleGalaxyPathUtils ansibleGalaxyPathUtils;

    @Inject
    public AnsibleGalaxyModulesResultBuilder(
            AnsibleGalaxyPathUtils ansibleGalaxyPathUtils) {

        this.ansibleGalaxyPathUtils = ansibleGalaxyPathUtils;
    }

    public AnsibleGalaxyModulesResult parse(final FluentAsset asset, String baseUrlRepo) {
        AnsibleGalaxyModulesResult result = new AnsibleGalaxyModulesResult();
        
        // Get component information if available
        FluentComponent component = (FluentComponent) asset.component().orElse(null);
        if (component != null) {
            String name = component.namespace() + "-" + component.name();
            String version = component.version();
            
            result.setHref(this.ansibleGalaxyPathUtils.parseHref(baseUrlRepo, name, version));
            result.setVersion(version);
        } else {
            // Fallback to parsing from path
            String path = asset.path();
            String version = "1.0.0"; // default version
            result.setHref(path);
            result.setVersion(version);
        }

        return result;
    }
}