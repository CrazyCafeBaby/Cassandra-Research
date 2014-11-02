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
package org.apache.cassandra.db.index;

import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.dht.LocalPartitioner;
import org.apache.cassandra.dht.LocalToken;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.concurrent.OpOrder;

/**
 * Implements a secondary index for a column family using a second column family
 * in which the row keys are indexed values, and column names are base row keys.
 */
public abstract class AbstractSimplePerColumnSecondaryIndex extends PerColumnSecondaryIndex
{
    protected ColumnFamilyStore indexCfs; //并不是索引所在的表对应的列族，而是index自己的列族

    // SecondaryIndex "forces" a set of ColumnDefinition. However this class (and thus it's subclass)
    // only support one def per index. So inline it in a field for 1) convenience and 2) avoid creating
    // an iterator each time we need to access it.
    // TODO: we should fix SecondaryIndex API
    protected ColumnDefinition columnDef;

    public void init()
    {
        assert baseCfs != null && columnDefs != null && columnDefs.size() == 1;

        columnDef = columnDefs.iterator().next();

        //索引实际上在内部也是当成一个列族存储的，
        //只不过这个列族名比较特殊，
        //此列族名为: 索引字段所在列族名 + "." + 索引名(如果没有索引名，就用索引字段名，建索引时可以不指定索引名)
        CellNameType indexComparator = SecondaryIndex.getIndexComparator(baseCfs.metadata, columnDef);
        CFMetaData indexedCfMetadata = CFMetaData.newIndexMetadata(baseCfs.metadata, columnDef, indexComparator);
        indexCfs = ColumnFamilyStore.createColumnFamilyStore(baseCfs.keyspace,
                                                             indexedCfMetadata.cfName,
                                                             new LocalPartitioner(getIndexKeyComparator()),
                                                             indexedCfMetadata);
    }

    protected AbstractType<?> getIndexKeyComparator()
    {
        return columnDef.type;
    }

    @Override
    public DecoratedKey getIndexKeyFor(ByteBuffer value)
    {
        return new BufferDecoratedKey(new LocalToken(getIndexKeyComparator(), value), value);
    }

    @Override
    String indexTypeForGrouping()
    {
        return "_internal_";
    }

    protected abstract CellName makeIndexColumnName(ByteBuffer rowKey, Cell cell);

    protected abstract ByteBuffer getIndexedValue(ByteBuffer rowKey, Cell cell);

    protected abstract AbstractType getExpressionComparator();

    public String expressionString(IndexExpression expr)
    {
        return String.format("'%s.%s %s %s'",
                             baseCfs.name,
                             getExpressionComparator().getString(expr.column),
                             expr.operator,
                             baseCfs.metadata.getColumnDefinition(expr.column).type.getString(expr.value));
    }

    public void delete(ByteBuffer rowKey, Cell cell, OpOrder.Group opGroup)
    {
        if (!cell.isLive())
            return;

        DecoratedKey valueKey = getIndexKeyFor(getIndexedValue(rowKey, cell));
        int localDeletionTime = (int) (System.currentTimeMillis() / 1000);
        ColumnFamily cfi = ArrayBackedSortedColumns.factory.create(indexCfs.metadata, false, 1);
        cfi.addTombstone(makeIndexColumnName(rowKey, cell), localDeletionTime, cell.timestamp());
        indexCfs.apply(valueKey, cfi, SecondaryIndexManager.nullUpdater, opGroup, null);
        if (logger.isDebugEnabled())
            logger.debug("removed index entry for cleaned-up value {}:{}", valueKey, cfi);
    }

    public void insert(ByteBuffer rowKey, Cell cell, OpOrder.Group opGroup)
    {
        //valueKey是索引字段值
        DecoratedKey valueKey = getIndexKeyFor(getIndexedValue(rowKey, cell));
        //对于CompositesIndexOnRegular类型，makeIndexColumnName返回的只有rowKey
        //对于KeysIndex，name其实就是rowKey
        ColumnFamily cfi = ArrayBackedSortedColumns.factory.create(indexCfs.metadata, false, 1);
        CellName name = makeIndexColumnName(rowKey, cell);
        if (cell instanceof ExpiringCell)
        {
            ExpiringCell ec = (ExpiringCell) cell;
            cfi.addColumn(new BufferExpiringCell(name, ByteBufferUtil.EMPTY_BYTE_BUFFER, ec.timestamp(), ec.getTimeToLive(), ec.getLocalDeletionTime()));
        }
        else
        {
            cfi.addColumn(new BufferCell(name, ByteBufferUtil.EMPTY_BYTE_BUFFER, cell.timestamp()));
        }
        if (logger.isDebugEnabled())
            logger.debug("applying index row {} in {}", indexCfs.metadata.getKeyValidator().getString(valueKey.getKey()), cfi);

        //从上面的valueKey和name对应rowKey作为一个Column放到ColumnFamily看出来
        //这里会保存valueKey和name，所以当按索引字段查找时，就能从valueKey中找到对应的name(就是rowKey)
        //传递nullUpdater时不会再进一步触发索引相关操作'
        indexCfs.apply(valueKey, cfi, SecondaryIndexManager.nullUpdater, opGroup, null);
    }

    public void update(ByteBuffer rowKey, Cell oldCol, Cell col, OpOrder.Group opGroup)
    {
        // insert the new value before removing the old one, so we never have a period
        // where the row is invisible to both queries (the opposite seems preferable); see CASSANDRA-5540                    
        insert(rowKey, col, opGroup);
        if (SecondaryIndexManager.shouldCleanupOldValue(oldCol, col))
            delete(rowKey, oldCol, opGroup);
    }

    public void removeIndex(ByteBuffer columnName)
    {
        indexCfs.invalidate();
    }

    public void forceBlockingFlush()
    {
        Future<?> wait;
        // we synchronise on the baseCfs to make sure we are ordered correctly with other flushes to the base CFS
        synchronized (baseCfs.getDataTracker())
        {
            wait = indexCfs.forceFlush();
        }
        FBUtilities.waitOnFuture(wait);
    }

    public void invalidate()
    {
        indexCfs.invalidate();
    }

    public void truncateBlocking(long truncatedAt)
    {
        indexCfs.discardSSTables(truncatedAt);
    }

    public ColumnFamilyStore getIndexCfs()
    {
       return indexCfs;
    }

    public String getIndexName()
    {
        return indexCfs.name;
    }

    public void reload()
    {
        indexCfs.metadata.reloadSecondaryIndexMetadata(baseCfs.metadata);
        indexCfs.reload();
    }
    
    public long estimateResultRows()
    {
        return getIndexCfs().getMeanColumns();
    } 
}
