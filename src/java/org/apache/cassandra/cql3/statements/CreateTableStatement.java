/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3.statements;

import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.exceptions.*;
import org.apache.commons.lang3.StringUtils;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;

import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.cql3.*;
import org.apache.cassandra.db.composites.*;
import org.apache.cassandra.db.ColumnFamilyType;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.AlreadyExistsException;
import org.apache.cassandra.io.compress.CompressionParameters;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.transport.Event;
import org.apache.cassandra.utils.ByteBufferUtil;

/** A <code>CREATE TABLE</code> parsed from a CQL query statement. */
public class CreateTableStatement extends SchemaAlteringStatement
{
    //与CLUSTERING_COLUMN的类型相关
    //1. 如果没有定义CLUSTERING_COLUMN并且使用CompactStorage，那么comparator是UTF8Type
    //2. 如果没有定义CLUSTERING_COLUMN并且未使用CompactStorage，那么comparator是CompositeType
    //   如果普通字段中没有Collection类型的字段，
    //   那么这个CompositeType只有一个UTF8Type，否则等于UTF8Type + ColumnToCollectionType
    //3. 如果CLUSTERING_COLUMN只有一个字段并且使用CompactStorage，那么comparator就是此字段的类型
    //4. 其他情况comparator是CompositeType
    //   4.1 使用CompactStorage时，CompositeType由CLUSTERING_COLUMN中的所有字段类型组成
    //   4.2 未使用CompactStorage时，
    //       如果普通字段中没有Collection类型的字段，
    //       CompositeType由CLUSTERING_COLUMN中的所有字段类型 + UTF8Type组成
    //       如果普通字段中有Collection类型的字段，
    //       则CompositeType由CLUSTERING_COLUMN中的所有字段类型 + UTF8Type + ColumnToCollectionType组成
    
    //    org.apache.cassandra.db.composites包中有东西都与聚簇列相关
    //
    //    两对关健词:
    //    Sparse(稀疏)与Dense(稠密) //与数据库中的稀疏索引和稠密索引有相似之处
    //    Simple(单一的)与Compound(复合的)
    //
    //    在建表时:
    //    1. 没有定义聚簇列
    //        1.1 使用了COMPACT STORAGE，那么使用SimpleSparseCellNameType(包装类型是UTF8Type)
    //        1.2 没有使用COMPACT STORAGE
    //            1.2.1 普通列中有集合类型(CollectionType)，那么使用CompoundSparseCellNameType.WithCollection
    //            1.2.2 没有，则使用CompoundSparseCellNameType
    //
    //    2. 定义了聚簇列
    //        2.1 只有一个聚簇列并且使用了COMPACT STORAGE，那么使用SimpleDenseCellNameType(包装类型是此聚簇列的类型)
    //        2.2 使用了COMPACT STORAGE，那么使用CompoundDenseCellNameType(包装类型是所有聚簇列的类型)
    //        2.3 没有使用COMPACT STORAGE
    //            2.3.1 普通列中有集合类型(CollectionType)，那么使用CompoundSparseCellNameType.WithCollection
    //            2.3.2 没有，则使用CompoundSparseCellNameType
    //
    //    由上面总结如下:
    //    只有定义了聚簇列的情况下，并且使用了COMPACT STORAGE才会使用SimpleDenseCellNameType或CompoundDenseCellNameType

    public CellNameType comparator;
    

    //1. 如果定义了CLUSTERING_COLUMN并且使用CompactStorage，同时没有其他普通字段, 那么defaultValidator是UTF8Type
    //2. 如果定义了CLUSTERING_COLUMN并且使用CompactStorage，同时有其他普通字段(只能有一个), 那么defaultValidator是此普通字段的类型
    //3. 如果没有定义CLUSTERING_COLUMN或者未使用CompactStorage
    //   3.1 如果普通字段中有Counter类型的字段, 那么defaultValidator是CounterColumnType
    //   3.2 其他情况，defaultValidator是BytesType
    private AbstractType<?> defaultValidator;
    
    //与PARTITION_KEY的类型相关
    //如果PARTITION_KEY只有一个字段，那么keyValidator就是此字段的类型
    //如果PARTITION_KEY有多个字段，那么keyValidator就是CompositeType
    private AbstractType<?> keyValidator;

    private final List<ByteBuffer> keyAliases = new ArrayList<ByteBuffer>();
    private final List<ByteBuffer> columnAliases = new ArrayList<ByteBuffer>();
    //1.
    //前提条件: 使用CompactStorage并且存在CLUSTERING_COLUMN字段时,
    //除了PARTITION_KEY和CLUSTERING_COLUMN字段之外，最多可以定义一个普通字段，
    //如果存在这样的普通字段那么valueAlias就是这个普通字段的字段名
    //如果不存在这样的普通字段，valueAlias的值是ByteBufferUtil.EMPTY_BYTE_BUFFER
    //2.
    //如果不满足前提条件，valueAlias是null
    private ByteBuffer valueAlias; 

    private boolean isDense;

    //普通列: org.apache.cassandra.config.ColumnDefinition.Kind.REGULAR
    private final Map<ColumnIdentifier, AbstractType> columns = new HashMap<ColumnIdentifier, AbstractType>();
    private final Set<ColumnIdentifier> staticColumns;
    private final CFPropDefs properties;
    private final boolean ifNotExists;

    public CreateTableStatement(CFName name, CFPropDefs properties, boolean ifNotExists, Set<ColumnIdentifier> staticColumns)
    {
        super(name);
        this.properties = properties;
        this.ifNotExists = ifNotExists;
        this.staticColumns = staticColumns;

//<<<<<<< HEAD
//        try
//        {
//            //如果没指定compression属性，则默认使用org.apache.cassandra.io.compress.LZ4Compressor
//            if (!this.properties.hasProperty(CFPropDefs.KW_COMPRESSION) && CFMetaData.DEFAULT_COMPRESSOR != null)
//                //注意compression属性对应的是一个Map不是一个字符串
//                this.properties.addProperty(CFPropDefs.KW_COMPRESSION,
//                                            new HashMap<String, String>()
//                                            {{
//                                                put(CompressionParameters.SSTABLE_COMPRESSION, CFMetaData.DEFAULT_COMPRESSOR);
//                                            }});
//        }
//        catch (SyntaxException e)
//        {
//            throw new AssertionError(e);
//        }
//=======
        if (!this.properties.hasProperty(CFPropDefs.KW_COMPRESSION) && CFMetaData.DEFAULT_COMPRESSOR != null)
            this.properties.addProperty(CFPropDefs.KW_COMPRESSION,
                                        new HashMap<String, String>()
                                        {{
                                            put(CompressionParameters.SSTABLE_COMPRESSION, CFMetaData.DEFAULT_COMPRESSOR);
                                        }});
    }

    public void checkAccess(ClientState state) throws UnauthorizedException, InvalidRequestException
    {
        state.hasKeyspaceAccess(keyspace(), Permission.CREATE);
    }

    public void validate(ClientState state)
    {
        // validated in announceMigration()
    }

    // Column definitions
    //只涉及普通列
    private List<ColumnDefinition> getColumns(CFMetaData cfm)
    {
        List<ColumnDefinition> columnDefs = new ArrayList<>(columns.size());
        //如果没有定义聚簇列时CompoundSparseCellNameType虽然comparator.clusteringPrefixSize()返回0
        //但是当在ColumnDefinition的构造函数调用cfm.getComponentComparator(componentIndex, kind))时会
        //触发org.apache.cassandra.db.composites.AbstractCompoundCellNameType.subtype(int)用的是fullType.get(0)
        //而fullType.get(0)刚好是CompoundSparseCellNameType.makeCType加入的columnNameType(也就是UTF8Type)
        Integer componentIndex = comparator.isCompound() ? comparator.clusteringPrefixSize() : null;
        for (Map.Entry<ColumnIdentifier, AbstractType> col : columns.entrySet())
        {
            ColumnIdentifier id = col.getKey();
            columnDefs.add(staticColumns.contains(id)
                           ? ColumnDefinition.staticDef(cfm, col.getKey().bytes, col.getValue(), componentIndex)
                           : ColumnDefinition.regularDef(cfm, col.getKey().bytes, col.getValue(), componentIndex));
        }

        return columnDefs;
    }

    public boolean announceMigration(boolean isLocalOnly) throws RequestValidationException
    {
        try
        {
            MigrationManager.announceNewColumnFamily(getCFMetaData(), isLocalOnly);
            return true;
        }
        catch (AlreadyExistsException e)
        {
            if (ifNotExists)
                return false;
            throw e;
        }
    }

    public Event.SchemaChange changeEvent()
    {
        return new Event.SchemaChange(Event.SchemaChange.Change.CREATED, Event.SchemaChange.Target.TABLE, keyspace(), columnFamily());
    }

    /**
     * Returns a CFMetaData instance based on the parameters parsed from this
     * <code>CREATE</code> statement, or defaults where applicable.
     *
     * @return a CFMetaData instance corresponding to the values parsed from this statement
     * @throws InvalidRequestException on failure to validate parsed parameters
     */
    public CFMetaData getCFMetaData() throws RequestValidationException
    {
        CFMetaData newCFMD;
        newCFMD = new CFMetaData(keyspace(),
                                 columnFamily(),
                                 ColumnFamilyType.Standard,
                                 comparator);
        applyPropertiesTo(newCFMD);
        return newCFMD;
    }

    public void applyPropertiesTo(CFMetaData cfmd) throws RequestValidationException
    {
        cfmd.defaultValidator(defaultValidator)
            .keyValidator(keyValidator)
            .addAllColumnDefinitions(getColumns(cfmd))
            .isDense(isDense);

//<<<<<<< HEAD
//        //只有普通字段(ColumnDefinition.Kind.REGULAR)才会像getColumns中那样在乎comparator的类型
//        //下面三个种类型的字段在CFMetaData.getComponentComparator(Integer, Kind)都返回UTF8Type，
//        //所以在调用ColumnIdentifier(ByteBuffer, AbstractType)时都不会有问题
//        cfmd.addColumnMetadataFromAliases(keyAliases, keyValidator, ColumnDefinition.Kind.PARTITION_KEY);
//        cfmd.addColumnMetadataFromAliases(columnAliases, comparator.asAbstractType(), ColumnDefinition.Kind.CLUSTERING_COLUMN);
//        //只有useCompactStorage为true且columnAliases不为empty时valueAlias才可能不为null
//=======
        addColumnMetadataFromAliases(cfmd, keyAliases, keyValidator, ColumnDefinition.Kind.PARTITION_KEY);
        addColumnMetadataFromAliases(cfmd, columnAliases, comparator.asAbstractType(), ColumnDefinition.Kind.CLUSTERING_COLUMN);
        if (valueAlias != null)
            addColumnMetadataFromAliases(cfmd, Collections.singletonList(valueAlias), defaultValidator, ColumnDefinition.Kind.COMPACT_VALUE);

        properties.applyToCFMetadata(cfmd);
    }
//<<<<<<< HEAD
//    /*
//          对于这样的CQL:
//    CREATE TABLE test (
//       table_name text,
//       index_name text,
//       index_name2 text,
//       PRIMARY KEY (table_name, index_name)
//    )WITH CLUSTERING ORDER BY (index_name DESC, index_name2 ASC) 
//     AND COMPACT STORAGE AND COMMENT='indexes that have been completed'");
//
//    keyAliases是table_name
//    columnAliases是index_name
//    definedOrdering是index_name, index_name2，其中index_name的reversed是true，index_name2是false
//    useCompactStorage是true
//    properties是COMMENT(通过org.apache.cassandra.cql3.PropertyDefinitions.addProperty(String, String)增加)
//    
//          如果PRIMARY KEY是PRIMARY KEY ((table_name,index_name2), index_name)
//          则keyAliases是(table_name,index_name2)
//    */
//=======

    private void addColumnMetadataFromAliases(CFMetaData cfm, List<ByteBuffer> aliases, AbstractType<?> comparator, ColumnDefinition.Kind kind)
    {
        if (comparator instanceof CompositeType)
        {
            CompositeType ct = (CompositeType)comparator;
            for (int i = 0; i < aliases.size(); ++i)
                if (aliases.get(i) != null)
                    cfm.addOrReplaceColumnDefinition(new ColumnDefinition(cfm, aliases.get(i), ct.types.get(i), i, kind));
        }
        else
        {
            assert aliases.size() <= 1;
            if (!aliases.isEmpty() && aliases.get(0) != null)
                cfm.addOrReplaceColumnDefinition(new ColumnDefinition(cfm, aliases.get(0), comparator, null, kind));
        }
    }

    public static class RawStatement extends CFStatement
    {
        //在org.apache.cassandra.cql3.CqlParser.cfamProperty(RawStatement)中把属性解析后放到properties字段中
        private final Map<ColumnIdentifier, CQL3Type.Raw> definitions = new HashMap<>();
        public final CFPropDefs properties = new CFPropDefs();
        //如CREATE TABLE IF NOT EXISTS Cats0 ( block_id uuid PRIMARY KEY, breed text, color text, short_hair boolean,"
        //+ "PRIMARY KEY ((block_id, breed), color, short_hair))
        //此时keyAliases.size是2，
        //其中keyAliases[0]是block_id
        //keyAliases[1]是(block_id, breed)
        //这在语法解析阶段是允许的，但是在CreateTableStatement.RawStatement.prepare()中才抛错，只需要keyAliases.size是1
        
        //每个表必须有主键，并且只能有一个
        //这个是错误的: CREATE TABLE IF NOT EXISTS Cats00 ( block_id uuid, breed text, color text, short_hair boolean)
        private final List<List<ColumnIdentifier>> keyAliases = new ArrayList<List<ColumnIdentifier>>();
        private final List<ColumnIdentifier> columnAliases = new ArrayList<ColumnIdentifier>();
        private final Map<ColumnIdentifier, Boolean> definedOrdering = new LinkedHashMap<ColumnIdentifier, Boolean>(); // Insertion ordering is important
        private final Set<ColumnIdentifier> staticColumns = new HashSet<ColumnIdentifier>();

        private boolean useCompactStorage;
        //允许相同的元素出现多个(按元素的hash值确定)
        //用来检查是否定义了多个同名的字段
        private final Multiset<ColumnIdentifier> definedNames = HashMultiset.create(1);

        private final boolean ifNotExists;

        public RawStatement(CFName name, boolean ifNotExists)
        {
            super(name);
            this.ifNotExists = ifNotExists;
        }

        /**
         * Transform this raw statement into a CreateTableStatement.
         */
        //参见my.test.cql3.statements.TableTest.test_RawStatement_prepare()的测试
        public ParsedStatement.Prepared prepare() throws RequestValidationException
        {
            // Column family name
            //列族名就是表名，必须是有效的标识符
            //不能在语法分析阶段检查出来，这里是QUOTED_NAME的场景(用双引号括起来)
            //如：CREATE TABLE IF NOT EXISTS \"t中t\" ( block_id uuid)
            if (!columnFamily().matches("\\w+"))
                //String.format中给的信息不够准确，不是[0-9A-Za-z]+，而是[a-zA-Z_0-9]+，少了一个下划线
                //IDENT在文法中也是有下划线的
                throw new InvalidRequestException(String.format("\"%s\" is not a valid table name (must be alphanumeric character only: [0-9A-Za-z]+)", columnFamily()));
            //列族名就是表名，不能超过48个字符
            if (columnFamily().length() > Schema.NAME_LENGTH)
                throw new InvalidRequestException(String.format("Table names shouldn't be more than %s characters long (got \"%s\")", Schema.NAME_LENGTH, columnFamily()));

            //定义了重复的字段
            for (Multiset.Entry<ColumnIdentifier> entry : definedNames.entrySet())
                if (entry.getCount() > 1)
                    throw new InvalidRequestException(String.format("Multiple definition of identifier %s", entry.getElement()));

            //验证是否是支持的属性
            //这样的用法是错误的:  WITH min_threshold=2 (Unknown property 'min_threshold')
            properties.validate();

            CreateTableStatement stmt = new CreateTableStatement(cfName, properties, ifNotExists, staticColumns);

            //以下代码用于确定stmt.columns的值
            ///////////////////////////////////////////////////////////////////////////
            boolean hasCounters = false;
            Map<ByteBuffer, CollectionType> definedMultiCellCollections = null;
            for (Map.Entry<ColumnIdentifier, CQL3Type.Raw> entry : definitions.entrySet())
            {
                ColumnIdentifier id = entry.getKey();
                CQL3Type pt = entry.getValue().prepare(keyspace());
                if (pt.isCollection() && ((CollectionType) pt.getType()).isMultiCell())
                {
                    if (definedMultiCellCollections == null)
                        definedMultiCellCollections = new HashMap<>();
                    definedMultiCellCollections.put(id.bytes, (CollectionType) pt.getType());
                }
                else if (entry.getValue().isCounter())
                    hasCounters = true;

                stmt.columns.put(id, pt.getType()); // we'll remove what is not a column below
            }


            //以下代码用于确定stmt.keyAliases和stmt.keyValidator的值(处理PARTITION_KEY)
            ///////////////////////////////////////////////////////////////////////////
            
            //每个表必须有主键，并且只能有一个
            if (keyAliases.isEmpty())
                throw new InvalidRequestException("No PRIMARY KEY specifed (exactly one required)");
            else if (keyAliases.size() > 1)
                throw new InvalidRequestException("Multiple PRIMARY KEYs specifed (exactly one required)");
            else if (hasCounters && properties.getDefaultTimeToLive() > 0)
                throw new InvalidRequestException("Cannot set default_time_to_live on a table with counters");

            List<ColumnIdentifier> kAliases = keyAliases.get(0);

            List<AbstractType<?>> keyTypes = new ArrayList<AbstractType<?>>(kAliases.size());
            for (ColumnIdentifier alias : kAliases)
            {
                stmt.keyAliases.add(alias.bytes);
                //通过PRIMARY KEY定义的部分，
                //例如: PRIMARY KEY ((block_id, breed), color, short_hair)
                //PRIMARY KEY由PARTITION_KEY和CLUSTERING_COLUMN组成，
                //其中(block_id, breed)是PARTITION_KEY，而(color, short_hair)是CLUSTERING_COLUMN
                //因为(block_id, breed)由大于1个字段组成，所以又叫composite PARTITION_KEY
                //getTypeAndRemove就是用来检查PARTITION_KEY和CLUSTERING_COLUMN的，这两种key中的字段类型不能是CollectionType
                
                //另外，此类的keyAliases对应PARTITION_KEY，而columnAliases对应CLUSTERING_COLUMN
                AbstractType<?> t = getTypeAndRemove(stmt.columns, alias);
                //主键字段不能是counter类型
                //例如这样是不行的: block_id counter PRIMARY KEY
                //但是配合使用CLUSTERING ORDER BY时，
                //如
                //CREATE TABLE IF NOT EXISTS test( block_id counter PRIMARY KEY, breed text) 
                //WITH CLUSTERING ORDER BY (block_id DESC)
                //此时getTypeAndRemove返回的是ReversedType，所以可以绕过这个异常，但是在后面的definedOrdering检查中还是会抛出异常
                //这个bug碰巧解决了
                if (t instanceof CounterColumnType)
                    throw new InvalidRequestException(String.format("counter type is not supported for PRIMARY KEY part %s", alias));
                if (staticColumns.contains(alias))
                    throw new InvalidRequestException(String.format("Static column %s cannot be part of the PRIMARY KEY", alias));
                keyTypes.add(t);
            }
            stmt.keyValidator = keyTypes.size() == 1 ? keyTypes.get(0) : CompositeType.getInstance(keyTypes);

            //以下代码用于确定stmt.comparator和stmt.columnAliases的值(处理CLUSTERING_COLUMN)
            ///////////////////////////////////////////////////////////////////////////
            // Dense means that no part of the comparator stores a CQL column name. This means
            // COMPACT STORAGE with at least one columnAliases (otherwise it's a thrift "static" CF).
            stmt.isDense = useCompactStorage && !columnAliases.isEmpty();
            // Handle column aliases
            //没有CLUSTERING_COLUMN或有CLUSTERING_COLUMN但是没有使用COMPACT STORAGE时都使用XxxSparseCellNameType(稀疏的)
            //其他情况使用XxxDenseCellNameType(稠密的)
            if (columnAliases.isEmpty())
            {
                if (useCompactStorage)
                {
                    // There should remain some column definition since it is a non-composite "static" CF
                    if (stmt.columns.isEmpty())
                        throw new InvalidRequestException("No definition found that is not part of the PRIMARY KEY");

                    if (definedMultiCellCollections != null)
                        throw new InvalidRequestException("Non-frozen collection types are not supported with COMPACT STORAGE");

                    stmt.comparator = new SimpleSparseCellNameType(UTF8Type.instance);
                }
                else
                {
                    //如: CREATE TABLE test
                    //+ " ( block_id uuid, breed text, short_hair boolean, emails set<text>," //
                    //+ " PRIMARY KEY ((block_id, breed)))
                    //此时stmt.comparator是一个CompositeType(包含UTF8Type和ColumnToCollectionType)
                    stmt.comparator = definedMultiCellCollections == null
                                    ? new CompoundSparseCellNameType(Collections.<AbstractType<?>>emptyList())
                                    : new CompoundSparseCellNameType.WithCollection(Collections.<AbstractType<?>>emptyList(), ColumnToCollectionType.getInstance(definedMultiCellCollections));
                }
            }
            else
            {
                // If we use compact storage and have only one alias, it is a
                // standard "dynamic" CF, otherwise it's a composite
                if (useCompactStorage && columnAliases.size() == 1)
                {
                    if (definedMultiCellCollections != null)
                        throw new InvalidRequestException("Collection types are not supported with COMPACT STORAGE");

                    ColumnIdentifier alias = columnAliases.get(0);
                    if (staticColumns.contains(alias))
                        throw new InvalidRequestException(String.format("Static column %s cannot be part of the PRIMARY KEY", alias));

                    stmt.columnAliases.add(alias.bytes);
                    AbstractType<?> at = getTypeAndRemove(stmt.columns, alias);
                    if (at instanceof CounterColumnType)
                        throw new InvalidRequestException(String.format("counter type is not supported for PRIMARY KEY part %s", stmt.columnAliases.get(0)));
                    stmt.comparator = new SimpleDenseCellNameType(at);
                }
                else
                {
                    List<AbstractType<?>> types = new ArrayList<AbstractType<?>>(columnAliases.size() + 1); //没有必要加1
                    for (ColumnIdentifier t : columnAliases)
                    {
                        stmt.columnAliases.add(t.bytes);

                        AbstractType<?> type = getTypeAndRemove(stmt.columns, t);
                        if (type instanceof CounterColumnType)
                            throw new InvalidRequestException(String.format("counter type is not supported for PRIMARY KEY part %s", t));
                        if (staticColumns.contains(t))
                            throw new InvalidRequestException(String.format("Static column %s cannot be part of the PRIMARY KEY", t));
                        types.add(type);
                    }

                    if (useCompactStorage)
                    {
                        if (definedMultiCellCollections != null)
                            throw new InvalidRequestException("Collection types are not supported with COMPACT STORAGE");

                        stmt.comparator = new CompoundDenseCellNameType(types);
                    }
                    else
                    {
                        stmt.comparator = definedMultiCellCollections == null
                                        ? new CompoundSparseCellNameType(types)
                                        : new CompoundSparseCellNameType.WithCollection(types, ColumnToCollectionType.getInstance(definedMultiCellCollections));
                    }
                }
            }
            
            //以下代码用于确定stmt.defaultValidator和stmt.valueAlias的值
            ///////////////////////////////////////////////////////////////////////////
            if (!staticColumns.isEmpty())
            {
                // Only CQL3 tables can have static columns
                if (useCompactStorage)
                    throw new InvalidRequestException("Static columns are not supported in COMPACT STORAGE tables");
                // Static columns only make sense if we have at least one clustering column. Otherwise everything is static anyway
                if (columnAliases.isEmpty())
                    throw new InvalidRequestException("Static columns are only useful (and thus allowed) if the table has at least one clustering column");
            }

            if (useCompactStorage && !stmt.columnAliases.isEmpty())
            {
                if (stmt.columns.isEmpty())
                {
                    // The only value we'll insert will be the empty one, so the default validator don't matter
                    stmt.defaultValidator = BytesType.instance;
                    // We need to distinguish between
                    //   * I'm upgrading from thrift so the valueAlias is null
                    //   * I've defined my table with only a PK (and the column value will be empty)
                    // So, we use an empty valueAlias (rather than null) for the second case
                    stmt.valueAlias = ByteBufferUtil.EMPTY_BYTE_BUFFER;
                }
                else
                {
                    if (stmt.columns.size() > 1)
                        throw new InvalidRequestException(String.format("COMPACT STORAGE with composite PRIMARY KEY allows no more than one column not part of the PRIMARY KEY (got: %s)", StringUtils.join(stmt.columns.keySet(), ", ")));
                    //使用最后那个字段
                    //如:
                    //CREATE TABLE IF NOT EXISTS " + tableName
                    //  (block_id uuid, breed text, short_hair boolean, f1 text"
                    //   PRIMARY KEY ((block_id, breed), short_hair)) WITH COMPACT STORAGE");
                    //则defaultValidator是text
                    //valueAlias是f1
                    Map.Entry<ColumnIdentifier, AbstractType> lastEntry = stmt.columns.entrySet().iterator().next();
                    stmt.defaultValidator = lastEntry.getValue();
                    stmt.valueAlias = lastEntry.getKey().bytes;
                    stmt.columns.remove(lastEntry.getKey()); //stmt.columns之后就空了
                }
            }
            else
            {
                // For compact, we are in the "static" case, so we need at least one column defined. For non-compact however, having
                // just the PK is fine since we have CQL3 row marker.
                //不可能出现这个，
                //因为前面if (columnAliases.isEmpty())　{　if (useCompactStorage)　{if (stmt.columns.isEmpty())
                //如果满足这三个if就提前抛异常了
                //如果columnAliases不是Empty，直接就进入了上一个if (useCompactStorage && !stmt.columnAliases.isEmpty())
                if (useCompactStorage && stmt.columns.isEmpty())
                    throw new InvalidRequestException("COMPACT STORAGE with non-composite PRIMARY KEY require one column not part of the PRIMARY KEY, none given");

                // There is no way to insert/access a column that is not defined for non-compact storage, so
                // the actual validator don't matter much (except that we want to recognize counter CF as limitation apply to them).
                stmt.defaultValidator = !stmt.columns.isEmpty() && (stmt.columns.values().iterator().next() instanceof CounterColumnType)
                    ? CounterColumnType.instance
                    : BytesType.instance;
            }

            
            
            //以下代码用于检查CLUSTERING ORDER子句是否合法
            ///////////////////////////////////////////////////////////////////////////
            //CLUSTERING ORDER中的字段只能是CLUSTERING_COLUMN包含的字段
            // If we give a clustering order, we must explicitly do so for all aliases and in the order of the PK
            if (!definedOrdering.isEmpty())
            {
                if (definedOrdering.size() > columnAliases.size())
                    throw new InvalidRequestException("Only CLUSTERING_COLUMN columns can be defined in CLUSTERING ORDER directive");

                int i = 0;
                for (ColumnIdentifier id : definedOrdering.keySet())
                {
                    ColumnIdentifier c = columnAliases.get(i);
                    if (!id.equals(c))
                    {
                        //排序key中的字段与CLUSTERING_COLUMN中的字段顺序必须一样
                        if (definedOrdering.containsKey(c))
                            throw new InvalidRequestException(String.format("The order of columns in the CLUSTERING ORDER directive must be the one of the CLUSTERING_COLUMN (%s must appear before %s)", c, id));
                        else //排序key中的字段必须是CLUSTERING_COLUMN中的字段
                            throw new InvalidRequestException(String.format("Missing CLUSTERING ORDER for column %s", c));
                    }
                    ++i;
                }
            }

            return new ParsedStatement.Prepared(stmt);
        }

        //PRIMARY KEY字段是PARTITION_KEY和CLUSTERING_COLUMN的统称
        //这个方法其实就是用来检查PARTITION_KEY和CLUSTERING_COLUMN中的字段(类型不能是CollectionType)
        //并且从org.apache.cassandra.cql3.statements.CreateTableStatement.columns中删除这些PRIMARY KEY字段
        private AbstractType<?> getTypeAndRemove(Map<ColumnIdentifier, AbstractType> columns, ColumnIdentifier t) throws InvalidRequestException
        {
            AbstractType type = columns.get(t);
            if (type == null)
                throw new InvalidRequestException(String.format("Unknown definition %s referenced in PRIMARY KEY", t));
            if (type.isCollection() && type.isMultiCell())
                throw new InvalidRequestException(String.format("Invalid collection type for PRIMARY KEY component %s", t));

            columns.remove(t);
            Boolean isReversed = definedOrdering.get(t);
            return isReversed != null && isReversed ? ReversedType.getInstance(type) : type;
        }

        //definitions中会有keyAliases和columnAliases的内容，
        //在prepare方法中会进行处理
        public void addDefinition(ColumnIdentifier def, CQL3Type.Raw type, boolean isStatic)
        {
            definedNames.add(def);
            definitions.put(def, type);
            if (isStatic)
                staticColumns.add(def);
        }
 
        //PARTITION_KEY可能由一个或多个字段构成，所以用List<ColumnIdentifier>
        //语法分析阶段可以出现多次PRIMARY KEY，当运行到prepare方法时才检查，
        //所以keyAliases的类型是List<List<ColumnIdentifier>>
        public void addKeyAliases(List<ColumnIdentifier> aliases)
        {
            keyAliases.add(aliases);
        }

        public void addColumnAlias(ColumnIdentifier alias)
        {
            columnAliases.add(alias);
        }

        public void setOrdering(ColumnIdentifier alias, boolean reversed)
        {
            definedOrdering.put(alias, reversed);
        }

        public void setCompactStorage()
        {
            useCompactStorage = true;
        }
    }
}
