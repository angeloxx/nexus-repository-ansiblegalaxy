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
package org.sonatype.nexus.plugins.ansiblegalaxy.internal;

import java.io.IOException;
import java.util.Optional;

import javax.inject.Inject;
import javax.inject.Named;

import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.blobstore.api.BlobRef;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.repository.content.facet.ContentFacetSupport;
import org.sonatype.nexus.repository.content.fluent.FluentAsset;
import org.sonatype.nexus.repository.content.fluent.FluentComponent;
import org.sonatype.nexus.repository.content.store.FormatStoreManager;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.BlobPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.transaction.Transactional;

import com.google.common.collect.ImmutableList;

import org.sonatype.nexus.plugins.ansiblegalaxy.AnsibleGalaxyFormat;
import org.sonatype.nexus.plugins.ansiblegalaxy.AssetKind;
import org.sonatype.nexus.plugins.ansiblegalaxy.internal.metadata.AnsibleGalaxyModule;
import org.sonatype.nexus.plugins.ansiblegalaxy.internal.util.AnsibleGalaxyPathUtils;
import org.sonatype.nexus.plugins.ansiblegalaxy.internal.util.TgzParser;


@Named(AnsibleGalaxyFormat.NAME)
public class AnsibleGalaxyContentFacetImpl
    extends ContentFacetSupport
    implements AnsibleGalaxyContentFacet
{
  private static final Iterable<HashAlgorithm> HASH_ALGORITHMS = ImmutableList.of(HashAlgorithm.SHA256);

  private final TgzParser tgzParser;

  @Inject
  public AnsibleGalaxyContentFacetImpl(
      @Named(AnsibleGalaxyFormat.NAME) final FormatStoreManager formatStoreManager,
      final TgzParser tgzParser)
  {
    super(formatStoreManager);
    this.tgzParser = tgzParser;
  }

  @Override
  @Transactional
  public Optional<Content> get(final String path) {
    return assets()
        .path(normalizeAssetPath(path))
        .find()
        .map(FluentAsset::download);
  }

  @Override
  @Transactional
  public Content put(final String path, final Payload content) throws IOException {
    try (TempBlob blob = blobs().ingest(content, HASH_ALGORITHMS)) {
      return save(path, blob, content);
    }
  }

  @Override
  @Transactional
  public boolean delete(final String path) {
    Optional<FluentAsset> asset = assets()
        .path(normalizeAssetPath(path))
        .find();
    
    if (asset.isPresent()) {
      FluentComponent component = (FluentComponent) asset.get().component().orElse(null);
      asset.get().delete();
      
      // Delete component if it has no more assets
      if (component != null && !component.assets().iterator().hasNext()) {
        component.delete();
      }
      return true;
    }
    return false;
  }

  @Override
  @Transactional
  public Optional<Content> getMetadata(final String path) {
    return get(path);
  }

  @Override
  @Transactional
  public Optional<AnsibleGalaxyModule> getModule(final String namespace, final String name, final String version) {
    Optional<FluentComponent> component = components()
        .name(name)
        .namespace(namespace)
        .version(version)
        .find();
        
    return component.map(this::componentToModule);
  }

  private Content save(final String path, final TempBlob blob, final Payload content) throws IOException {
    AssetKind assetKind = AssetKind.COLLECTION_ARTIFACT;
    FluentComponent component = null;
    
    if (path.endsWith(".tar.gz")) {
      // TODO: Parse tarball to extract module metadata
      // For now, create basic component from path
      String[] parts = path.split("-");
      if (parts.length >= 3) {
        String namespace = parts[0];
        String name = parts[1];
        String version = parts[parts.length - 1].replace(".tar.gz", "");
        component = components()
            .name(name)
            .namespace(namespace)
            .version(version)
            .getOrCreate();
      }
    }
    
    FluentAsset asset = assets()
        .path(normalizeAssetPath(path))
        .kind(assetKind.name())
        .component(component)
        .blob(blob)
        .save();
    
    return toContent(asset, blob.getBlob());
  }


  private AnsibleGalaxyModule componentToModule(final FluentComponent component) {
    AnsibleGalaxyModule module = new AnsibleGalaxyModule();
    module.setVersion(component.version());
    module.setNamespace(new AnsibleGalaxyModule.AnsibleGalaxyModuleNamespace().setName(component.namespace()));
    module.setCollection(new AnsibleGalaxyModule.AnsibleGalaxyModuleCollection().setName(component.name()));
    return module;
  }

  private Content toContent(final FluentAsset asset, final Blob blob) {
    AttributesMap attributesMap = new AttributesMap();
    attributesMap.set(Content.CONTENT_LAST_MODIFIED, asset.lastUpdated());
    attributesMap.set(Content.CONTENT_ETAG, asset.attributes().get(Content.CONTENT_ETAG, String.class));
    attributesMap.set("assetKind", asset.kind());
    
    BlobPayload payload = new BlobPayload(blob, asset.blob().get().contentType());
    Content content = new Content(payload);
    content.getAttributes().backing().putAll(attributesMap.backing());
    return content;
  }

  private String normalizeAssetPath(final String path) {
    return path.startsWith("/") ? path.substring(1) : path;
  }
}