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
package org.sonatype.nexus.plugins.ansiblegalaxy.internal.util;

import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.sonatype.goodies.common.Loggers;
import org.sonatype.nexus.blobstore.api.Blob;
import org.sonatype.nexus.common.collect.AttributesMap;
import org.sonatype.nexus.common.hash.HashAlgorithm;
import org.sonatype.nexus.plugins.ansiblegalaxy.AssetKind;
import org.sonatype.nexus.plugins.ansiblegalaxy.internal.metadata.AnsibleGalaxyAttributes;
import org.sonatype.nexus.repository.Repository;
import org.sonatype.nexus.repository.search.SearchRequest;
import org.sonatype.nexus.repository.storage.*;
import org.sonatype.nexus.transaction.Transactional;
import org.sonatype.nexus.repository.view.Content;
import org.sonatype.nexus.repository.view.Payload;
import org.sonatype.nexus.repository.view.payloads.BlobPayload;
import org.sonatype.nexus.repository.view.payloads.TempBlob;
import org.sonatype.nexus.transaction.UnitOfWork;

import javax.annotation.Nullable;
import javax.inject.Named;
import java.io.IOException;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.sonatype.nexus.repository.storage.AssetEntityAdapter.P_ASSET_KIND;
import static org.sonatype.nexus.repository.storage.ComponentEntityAdapter.P_GROUP;
import static org.sonatype.nexus.repository.storage.ComponentEntityAdapter.P_VERSION;
import static org.sonatype.nexus.repository.storage.MetadataNodeEntityAdapter.P_NAME;

/**
 * @since 0.0.1
 */
@Named
public class AnsibleGalaxyDataAccess {
    public static final List<HashAlgorithm> HASH_ALGORITHMS = ImmutableList.of(HashAlgorithm.SHA256, HashAlgorithm.SHA1);

    private final Logger log = Loggers.getLogger(getClass());


    private AnsibleGalaxyDataAccess() {
    }

    /**
     * Find an asset by its name.
     *
     * @return found asset or null if not found
     */
    @Nullable
    public Asset findAsset(final StorageTx tx, final Bucket bucket, final String assetName) {
        return tx.findAssetWithProperty(MetadataNodeEntityAdapter.P_NAME, assetName, bucket);
    }

    /**
     * Save an asset and create blob.
     *
     * @return blob content
     */
    @Transactional
    public Content maybeCreateAndSaveAsset(
            final Repository repository,
            final String assetPath,
            final AssetKind assetKind,
            final TempBlob tempBlob,
            final Payload payload) throws IOException {
        StorageTx tx = UnitOfWork.currentTx();
        Bucket bucket = tx.findBucket(repository);

        Asset asset = findAsset(tx, bucket, assetPath);
        if (asset == null) {
            asset = tx.createAsset(bucket, repository.getFormat());
            asset.name(assetPath);
            asset.formatAttributes().set(P_ASSET_KIND, assetKind.name());
        }
        return saveAsset(tx, asset, tempBlob, payload);
    }

    /**
     * Save an asset and create blob.
     *
     * @return blob content
     */
    public Content saveAsset(
            final StorageTx tx,
            final Asset asset,
            final TempBlob contentSupplier,
            final Payload payload) throws IOException {
        AttributesMap contentAttributes = null;
        String contentType = null;
        if (payload instanceof Content) {
            contentAttributes = ((Content) payload).getAttributes();
            contentType = payload.getContentType();
        }
        return saveAsset(tx, asset, contentSupplier, contentType, contentAttributes);
    }

    /**
     * Save an asset and create blob.
     *
     * @return blob content
     */
    public Content saveAsset(
            final StorageTx tx,
            final Asset asset,
            final TempBlob contentSupplier,
            final String contentType,
            @Nullable final AttributesMap contentAttributes) throws IOException {
        log.debug("saving asset: {}", asset.name());
        Content.applyToAsset(asset, Content.maintainLastModified(asset, contentAttributes));
        AssetBlob assetBlob = tx.setBlob(asset, asset.name(), contentSupplier, HASH_ALGORITHMS, null, contentType, false);
        tx.saveAsset(asset);
        return toContent(asset, assetBlob.getBlob());
    }

    /**
     * Convert an asset blob to {@link Content}.
     *
     * @return content of asset blob
     */
    public Content toContent(final Asset asset, final Blob blob) {
        Content content = new Content(new BlobPayload(blob, asset.requireContentType()));
        Content.extractFromAsset(asset, HASH_ALGORITHMS, content.getAttributes());
        return content;
    }

    @Transactional
    public Content maybeCreateAndSaveComponent(
            final Repository repository,
            final AnsibleGalaxyAttributes ansibleGalaxyAttributes,
            final String assetPath,
            final TempBlob tempBlob,
            final Payload payload,
            final AssetKind assetKind) throws IOException {
        StorageTx tx = UnitOfWork.currentTx();
        Bucket bucket = tx.findBucket(repository);

        Component component = findComponent(tx, repository, ansibleGalaxyAttributes.getGroup(),
                ansibleGalaxyAttributes.getName(), ansibleGalaxyAttributes.getVersion());

        if (component == null) {
            log.debug("creating component: {}, {}, {}", ansibleGalaxyAttributes.getGroup(), ansibleGalaxyAttributes.getName(),
                    ansibleGalaxyAttributes.getVersion());
            component = tx.createComponent(bucket, repository.getFormat()).group(ansibleGalaxyAttributes.getGroup())
                    .name(ansibleGalaxyAttributes.getName()).version(ansibleGalaxyAttributes.getVersion());
            tx.saveComponent(component);
        }

        Asset asset = findAsset(tx, bucket, assetPath);
        if (asset == null) {
            asset = tx.createAsset(bucket, component);
            asset.name(assetPath);
            asset.formatAttributes().set(P_ASSET_KIND, assetKind.name());
        }
        return saveAsset(tx, asset, tempBlob, payload);
    }

    @Nullable
    public Component findComponent(
            final StorageTx tx,
            final Repository repository,
            final String group,
            final String name,
            final String version) {
        Iterable<Component> components = tx.findComponents(
                Query.builder().where(P_GROUP).eq(group).and(P_NAME).eq(name).and(P_VERSION).eq(version).build(),
                singletonList(repository));
        if (components.iterator().hasNext()) {
            return components.iterator().next();
        }
        return null;
    }

    public SearchRequest buildQueryByName(final Repository repository,
                                          final String module) {
        return new SearchRequest.Builder().build();
    }

    public SearchRequest buildPaginatedQueryByName(
            final Repository repository,
            final String module,
            String sorting,
            int limit,
            int offset) {
        return new SearchRequest.Builder().build();
    }
}
