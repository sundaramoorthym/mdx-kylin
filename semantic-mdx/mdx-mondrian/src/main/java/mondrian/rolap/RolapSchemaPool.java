/*
// This software is subject to the terms of the Eclipse Public License v1.0
// Agreement, available at the following URL:
// http://www.eclipse.org/legal/epl-v10.html.
// You must accept the terms of that agreement to use this software.
//
// Copyright (C) 2001-2005 Julian Hyde and others
// Copyright (C) 2005-2012 Pentaho and others
// All Rights Reserved.
*/
package mondrian.rolap;

import io.kylin.mdx.rolap.cache.CacheManager;
import mondrian.olap.MondrianCacheControl;
import mondrian.olap.Util;
import mondrian.resource.MondrianResource;
import mondrian.rolap.aggmatcher.JdbcSchema;
import mondrian.spi.DynamicSchemaProcessor;
import mondrian.util.*;

import mondrian.xmla.XmlaRequestContext;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.lang.ref.*;
import java.util.*;
import javax.sql.DataSource;

/**
 * A collection of schemas, identified by their connection properties
 * (catalog name, JDBC URL, and so forth).
 *
 * <p>To lookup a schema, call
 * <code>RolapSchemaPool.{@link #instance}().{@link #get}</code>.</p>
 */
public class RolapSchemaPool {
    static final Logger LOGGER = Logger.getLogger(RolapSchemaPool.class);

    private static final RolapSchemaPool INSTANCE = new RolapSchemaPool();

    private final Map<SchemaKey, SoftReference<RolapSchema>> mapKeyToSchema =
        new HashMap<SchemaKey, SoftReference<RolapSchema>>();

    // REVIEW: This map is now considered unsafe. If two schemas have identical
    // metadata but a different underlying database connection, we should not
    // share a cache. Since SchemaContentKey is now a hash of the schema
    // definition, this field can probably be removed.
//    private final Map<ByteString, SoftReference<RolapSchema>> mapMd5ToSchema =
//        new HashMap<ByteString, SoftReference<RolapSchema>>();

    private RolapSchemaPool() {
    }

    public static RolapSchemaPool instance() {
        return INSTANCE;
    }

    synchronized RolapSchema get(
        final String catalogUrl,
        final String connectionKey,
        final String jdbcUser,
        final String dataSourceStr,
        final Util.PropertyList connectInfo)
    {
        return get(
            catalogUrl,
            connectionKey,
            jdbcUser,
            dataSourceStr,
            null,
            connectInfo);
    }

    synchronized RolapSchema get(
        final String catalogUrl,
        final DataSource dataSource,
        final Util.PropertyList connectInfo)
    {
        return get(
            catalogUrl,
            null,
            null,
            null,
            dataSource,
            connectInfo);
    }

    private RolapSchema get(
        final String catalogUrl,
        final String connectionKey,
        final String jdbcUser,
        final String dataSourceStr,
        final DataSource dataSource,
        final Util.PropertyList connectInfo)
    {
        final String dialectClassName =
            connectInfo.get(RolapConnectionProperties.Dialect.name());
        final String connectionUuidStr = connectInfo.get(
            RolapConnectionProperties.JdbcConnectionUuid.name());
        final boolean useSchemaPool =
            Boolean.parseBoolean(
                connectInfo.get(
                    RolapConnectionProperties.UseSchemaPool.name(),
                    "true"));
        final boolean useContentChecksum =
            Boolean.parseBoolean(
                connectInfo.get(
                    RolapConnectionProperties.UseContentChecksum.name()));
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "get: catalog=" + catalogUrl
                + ", connectionKey=" + connectionKey
                + ", jdbcUser=" + jdbcUser
                + ", dataSourceStr=" + dataSourceStr
                + ", dataSource=" + dataSource
                + ", dialect=" + dialectClassName
                + ", jdbcConnectionUuid=" + connectionUuidStr
                + ", useSchemaPool=" + useSchemaPool
                + ", useContentChecksum=" + useContentChecksum
                + ", map-size=" + mapKeyToSchema.size());
//                + ", md5-map-size=" + mapMd5ToSchema.size());
        }
        final ConnectionKey connectionKey1 =
            ConnectionKey.create(
                connectionUuidStr,
                dataSource,
                catalogUrl,
                dialectClassName,
                connectionKey,
                jdbcUser,
                dataSourceStr);

        final String catalogStr = getSchemaContent(connectInfo, catalogUrl);
        final SchemaContentKey schemaContentKey =
            SchemaContentKey.create(connectInfo, catalogUrl, catalogStr);
        final SchemaKey key =
            new SchemaKey(
                schemaContentKey,
                connectionKey1);

        // Use the schema pool unless "UseSchemaPool" is explicitly false.
        RolapSchema schema = null;
        if (!useSchemaPool) {
            schema =
                RolapSchemaLoader.createSchema(
                    key,
                    null,
                    catalogUrl,
                    catalogStr,
                    connectInfo,
                    dataSource);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "create (no pool): schema-name=" + schema.name
                    + ", schema-id="
                    + Integer.toHexString(System.identityHashCode(schema)));
            }
            return schema;
        }

        if (useContentChecksum) {
            // datasource 密码刷新，不能共用 schema
            final ByteString md5Bytes =
                new ByteString(Util.digestMd5(catalogStr + catalogUrl));
            final SoftReference<RolapSchema> ref =
                mapKeyToSchema.get(key);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(
                    "get(key=" + key
                    + ") returned " + toString(ref));
            }

            if (ref != null) {
                schema = ref.get();
                // schema changed
                if (schema == null || !schema.md5Bytes.equals(md5Bytes)) {
                    // clear out the reference since schema is null
                    if (schema != null) {
                        CacheManager.getCacheManager().expireAllForOneSchema(schema.getName(), schema.getChecksum());
                    }
                    mapKeyToSchema.remove(key);
//                    mapMd5ToSchema.remove(md5Bytes);
                }
            }


            if (schema == null) {
                schema = RolapSchemaLoader.createSchema(
                    key,
                    md5Bytes,
                    catalogUrl,
                    catalogStr,
                    connectInfo,
                    dataSource);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(
                        "create: schema-name=" + schema.name
                        + ", schema-id=" + System.identityHashCode(schema));
                }
                putSchema(schema, md5Bytes);
            }
            return schema;
        }

        SoftReference<RolapSchema> ref = mapKeyToSchema.get(key);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "get(key=" + key
                + ") returned " + toString(ref));
        }
        if (ref != null) {
            schema = ref.get();
            if (schema == null) {
                mapKeyToSchema.remove(key);
            }
        }

        if (schema == null) {
            schema = RolapSchemaLoader.createSchema(
                key,
                null,
                catalogUrl,
                catalogStr,
                connectInfo,
                dataSource);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("create: " + schema);
            }
            putSchema(schema, null);
        }

        return schema;
    }

    private void putSchema(
        final RolapSchema schema,
        final ByteString md5Bytes)
    {
        SoftReference<RolapSchema> ref =
            new SoftReference<RolapSchema>(schema);
//        if (md5Bytes != null) {
//            mapMd5ToSchema.put(md5Bytes, ref);
//        }
        mapKeyToSchema.put(schema.key, ref);
        MondrianCacheControl.putSchemaKey(XmlaRequestContext.getContext().currentProject, schema);

        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(
                "put: schema=" + schema
                + ", key=" + schema.key
                + ", checksum=" + md5Bytes
                + ", map-size=" + mapKeyToSchema.size());
//                + ", md5-map-size=" + mapMd5ToSchema.size());
        }
    }

    private static String getSchemaContent(
        final Util.PropertyList connectInfo,
        final String catalogUrl)
    {
        // We will return the first of the following:
        //  1. CatalogContent property if set
        //  2. DynamicSchemaProcessor#processSchema if set
        //  3. Util.readVirtualFileAsString(catalogUrl)

        String catalogStr = connectInfo.get(
            RolapConnectionProperties.CatalogContent.name());

        if (Util.isEmpty(catalogStr)) {
            if (Util.isEmpty(catalogUrl)) {
                throw MondrianResource.instance()
                    .ConnectStringMandatoryProperties.ex(
                        RolapConnectionProperties.Catalog.name(),
                        RolapConnectionProperties.CatalogContent.name());
            }
            // check for a DynamicSchemaProcessor
            String dynProcName = connectInfo.get(
                RolapConnectionProperties.DynamicSchemaProcessor.name());
            if (!Util.isEmpty(dynProcName)) {
                catalogStr =
                    processDynamicSchema(
                        dynProcName, catalogUrl, connectInfo);
            }

            if (Util.isEmpty(catalogStr)) {
                // read schema from file
                try {
                    catalogStr = Util.readVirtualFileAsString(catalogUrl);
                } catch (IOException e) {
                    throw Util.newError(
                        e,
                        "loading schema from url " + catalogUrl);
                }
            }
        }

        return catalogStr;
    }

    private static String processDynamicSchema(
        final String dynProcName,
        final String catalogUrl,
        final Util.PropertyList connectInfo)
    {
        if (RolapSchema.LOGGER.isDebugEnabled()) {
            RolapSchema.LOGGER.debug(
                "Pool.get: create schema \"" + catalogUrl
                + "\" using dynamic processor");
        }
        try {
            final DynamicSchemaProcessor dynProc =
                ClassResolver.INSTANCE.instantiateSafe(dynProcName);
            return dynProc.processSchema(catalogUrl, connectInfo);
        } catch (Exception e) {
            throw Util.newError(
                e,
                "loading DynamicSchemaProcessor " + dynProcName);
        }
    }

    synchronized void remove(
        final String catalogUrl,
        final String dialectClassName,
        final String connectionKey,
        final String jdbcUser,
        final String dataSourceStr)
    {
        final SchemaContentKey schemaContentKey =
            SchemaContentKey.create(
                new Util.PropertyList(),
                catalogUrl,
                null);
        final ConnectionKey connectionUuid =
            ConnectionKey.create(
                null,
                null,
                catalogUrl,
                dialectClassName,
                connectionKey,
                jdbcUser,
                dataSourceStr);
        final SchemaKey key =
            new SchemaKey(schemaContentKey, connectionUuid);
        if (RolapSchema.LOGGER.isDebugEnabled()) {
            RolapSchema.LOGGER.debug(
                "Pool.remove: schema \"" + catalogUrl
                + "\" and datasource string \"" + dataSourceStr + "\"");
        }
        remove(key);
    }

    synchronized void remove(
        final String catalogUrl,
        final String dialectClassName,
        final DataSource dataSource)
    {
        final SchemaContentKey schemaContentKey =
            SchemaContentKey.create(
                new Util.PropertyList(),
                catalogUrl,
                null);
        final ConnectionKey connectionKey =
            ConnectionKey.create(
                null,
                dataSource,
                catalogUrl,
                dialectClassName,
                null,
                null,
                null);
        final SchemaKey key =
            new SchemaKey(schemaContentKey, connectionKey);
        if (RolapSchema.LOGGER.isDebugEnabled()) {
            RolapSchema.LOGGER.debug(
                "Pool.remove: schema \"" + catalogUrl
                + "\" and datasource object");
        }
        remove(key);
    }

    synchronized void remove(RolapSchema schema) {
        if (schema != null) {
            if (RolapSchema.LOGGER.isDebugEnabled()) {
                RolapSchema.LOGGER.debug(
                    "Pool.remove: schema \"" + schema.getName()
                    + "\" and datasource object");
            }
            remove(schema.key);
        }
    }

    void remove(SchemaKey key) {
        SoftReference<RolapSchema> ref = mapKeyToSchema.get(key);
        if (ref != null) {
            RolapSchema schema = ref.get();
            if (schema != null) {
//                mapMd5ToSchema.remove(schema.getChecksum());
                schema.finalCleanUp();
            }
        }
        mapKeyToSchema.remove(key);
    }

    public synchronized void refreshCachedSchema(SchemaKey schemaKey) {
        SoftReference<RolapSchema> ref = mapKeyToSchema.get(schemaKey);
        if (ref != null) {
            RolapSchema schema = ref.get();
            refreshDefaultMembers(schema);
            clearRolapStarCachedAggregations(schema);
        }
    }

    private void refreshDefaultMembers(RolapSchema schema) {
        if (schema != null) {
            RolapSchemaLoader rolapSchemaLoader = new RolapSchemaLoader(schema);
            for (RolapCube cube : schema.getCubeList()) {
                rolapSchemaLoader.updateDefaultMembers(cube);
            }
        }
    }

    private void clearRolapStarCachedAggregations(RolapSchema schema) {
        if (schema != null) {
            for (RolapStar star : schema.getStars()) {
                star.clearCachedAggregations(true);
            }
        }
    }

    synchronized void clear() {
        if (RolapSchema.LOGGER.isDebugEnabled()) {
            RolapSchema.LOGGER.debug("Pool.clear: clearing all RolapSchemas");
        }

        for (SoftReference<RolapSchema> ref : mapKeyToSchema.values()) {
            if (ref != null) {
                RolapSchema schema = ref.get();
                if (schema != null) {
                    schema.finalCleanUp();
                }
            }
        }
        mapKeyToSchema.clear();
//        mapMd5ToSchema.clear();
        JdbcSchema.clearAllDBs();
    }

    public synchronized void refreshAllCachedSchemas() {
        for (Map.Entry<SchemaKey, SoftReference<RolapSchema>> entry : mapKeyToSchema.entrySet()) {
            SoftReference<RolapSchema> ref = entry.getValue();
            if (ref != null) {
                RolapSchema schema = ref.get();
                refreshDefaultMembers(schema);
                clearRolapStarCachedAggregations(schema);
            }
        }
    }

    /**
     * Returns a list of schemas in this pool.
     *
     * @return List of schemas in this pool
     */
    public synchronized List<RolapSchema> getRolapSchemas() {
        List<RolapSchema> list = new ArrayList<RolapSchema>();
        for (RolapSchema schema
            : Util.GcIterator.over(mapKeyToSchema.values()))
        {
            list.add(schema);
        }
        return list;
    }

    synchronized boolean contains(RolapSchema rolapSchema) {
        return mapKeyToSchema.containsKey(rolapSchema.key);
    }

    private static <T> String toString(Reference<T> ref) {
        if (ref == null) {
            return "null";
        } else {
            T t = ref.get();
            if (t == null) {
                return "ref(null)";
            } else {
                return "ref(" + t
                    + ", id=" + Integer.toHexString(System.identityHashCode(t))
                    + ")";
            }
        }
    }
}

// End RolapSchemaPool.java
