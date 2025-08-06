/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2020-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.plugins.ansiblegalaxy.internal.hosted;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.plugins.ansiblegalaxy.AssetKind;
import org.sonatype.nexus.plugins.ansiblegalaxy.internal.AnsibleGalaxyContentFacet;
import org.sonatype.nexus.plugins.ansiblegalaxy.internal.metadata.*;
import org.sonatype.nexus.plugins.ansiblegalaxy.internal.util.AnsibleGalaxyPathUtils;
import org.sonatype.nexus.repository.FacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.ContentTypes;
import org.sonatype.nexus.repository.view.Context;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher;
import org.sonatype.nexus.repository.view.payloads.BytesPayload;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.StreamSupport;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.nexus.plugins.ansiblegalaxy.AssetKind.COLLECTION_ARTIFACT;

@Named
public class AnsibleGalaxyHostedFacetImpl
        extends FacetSupport
        implements AnsibleGalaxyHostedFacet {

    private final AnsibleGalaxyModulesResultBuilder builder;
    private final AnsibleGalaxyModulesBuilder moduleBuilder;
    private final AnsibleGalaxyPathUtils ansibleGalaxyPathUtils;

    @Inject
    public AnsibleGalaxyHostedFacetImpl(
                                        AnsibleGalaxyModulesResultBuilder builder,
                                        AnsibleGalaxyModulesBuilder moduleBuilder,
                                        AnsibleGalaxyPathUtils ansibleGalaxyPathUtils) {
        this.builder = builder;
        this.moduleBuilder = moduleBuilder;
        this.ansibleGalaxyPathUtils = ansibleGalaxyPathUtils;
    }

    @Override
    public Content get(final String path) {
        checkNotNull(path);
        return content().get(path).orElse(null);
    }

    @Override
    public void put(final String path, final Payload content, final AssetKind assetKind) throws IOException {
        checkNotNull(path);
        checkNotNull(content);

        if (assetKind != COLLECTION_ARTIFACT) {
            throw new IllegalArgumentException("Unsupported AssetKind");
        }
        
        content().put(path, content);
    }

    @Override
    public boolean delete(final String path) throws IOException {
        return content().delete(path);
    }

    @Override
    public Content searchByName(Context context, String user, String module) {
        AnsibleGalaxyModuleName result = new AnsibleGalaxyModuleName();
        result.setVersions_url(ansibleGalaxyPathUtils.collectionNamePath(user, module))
                .setName(module);
        ObjectMapper objectMapper = new ObjectMapper();
        String results = null;
        try {
            results = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return new Content(new BytesPayload(results.getBytes(), ContentTypes.APPLICATION_JSON));
    }

    @Override
    public Content searchVersionsByName(final Context context, String user, String module) {
        AnsibleGalaxyModules releases = getModuleReleases(context, user, module);

        ObjectMapper objectMapper = new ObjectMapper();

        try {
            String results = objectMapper.writeValueAsString(releases);
            return new Content(new BytesPayload(results.getBytes(), ContentTypes.APPLICATION_JSON));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }

    private AnsibleGalaxyModules getModuleReleases(
            final Context context,
            String user, String module) {
        
        String baseUrlRepo = getRepository().getUrl();
        
        // Find all assets matching the pattern
        List<FluentAsset> matchingAssets = new ArrayList<>();
        Map<String, Object> filterParams = new HashMap<>();
        filterParams.put("user", user);
        filterParams.put("module", module);
        content().assets()
            .byFilter("path LIKE '%#{filterParams.user}-#{filterParams.module}%'", filterParams)
            .browse(1000, null)
            .forEach(matchingAssets::add);
        
        long count = matchingAssets.size();
        
        AnsibleGalaxyModules releases = moduleBuilder.parse(count, count, 0, context);
        for (FluentAsset asset : matchingAssets) {
            AnsibleGalaxyModulesResult result = builder.parse(asset, baseUrlRepo);
            releases.addResult(result);
        }

        return releases;
    }

    @Override
    public Content moduleByNameAndVersion(Context context, String user, String module, String version) {
        
        String baseUrlRepo = getRepository().getUrl();
        FluentAsset asset = null;
        String asset_version = null;
        
        // Find assets matching the pattern
        Iterable<FluentAsset> assets = content().assets().browse(1000, null);
        for (FluentAsset assetX : assets) {
            if (assetX.path().contains(String.format("%s-%s", user, module))) {
                asset = assetX;
                // Get version from component if available
                FluentComponent component = (FluentComponent) assetX.component().orElse(null);
                if (component != null) {
                    asset_version = component.version();
                    if (asset_version.equals(version)) { 
                        break; 
                    }
                }
            }
        }

        if (asset == null || asset_version == null) {
            return null;
        }

        AnsibleGalaxyModule result = new AnsibleGalaxyModule();
        result.setVersion(asset_version)
                .setHref(ansibleGalaxyPathUtils.parseHref(baseUrlRepo, user + "-" + module, asset_version))
                .setDownload_url(ansibleGalaxyPathUtils.download(baseUrlRepo, user, module, version))
                .setNamespace(new AnsibleGalaxyModule.AnsibleGalaxyModuleNamespace().setName(user))
                .setCollection(new AnsibleGalaxyModule.AnsibleGalaxyModuleCollection().setName(module))
                .setMetadata(new AnsibleGalaxyModule.AnsibleGalaxyModuleMetadata().setDependencies(new Object()))
                .setArtifact(new AnsibleGalaxyModule.AnsibleGalaxyModuleArtifact().setSha256(
                    asset.blob().map(blob -> blob.checksums().get("sha256")).orElse("")
                ));
                
        ObjectMapper objectMapper = new ObjectMapper();
        String results = null;
        try {
            results = objectMapper.writeValueAsString(result);
            return new Content(new BytesPayload(results.getBytes(), ContentTypes.APPLICATION_JSON));
        } catch (JsonProcessingException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    private AnsibleGalaxyContentFacet content() {
        return getRepository().facet(AnsibleGalaxyContentFacet.class);
    }
}