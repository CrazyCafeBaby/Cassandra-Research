内部有3个keyspace:
system
system_traces
system_auth


内部有23个表(或称列族):
属于system有18个:
====================================================================
//有７个schema表，并且它们的gc_grace_seconds都是一周，分区key都是keyspace_name
	CREATE TABLE schema_keyspaces (
		keyspace_name text,
		durable_writes boolean,
		strategy_class text,
		strategy_options text,
		PRIMARY KEY (keyspace_name)
	) WITH COMPACT STORAGE

	CREATE TABLE schema_columnfamilies (
		keyspace_name text,
		columnfamily_name text,
		type text,
		comparator text,
		subcomparator text,
		comment text,
		read_repair_chance double,
		local_read_repair_chance double,
		replicate_on_write boolean,
		gc_grace_seconds int, //grace是宽限的意思
		default_validator text,
		key_validator text,
		min_compaction_threshold int,
		max_compaction_threshold int,
		memtable_flush_period_in_ms int,
		key_aliases text,
		bloom_filter_fp_chance double,
		caching text,
		default_time_to_live int,
		compaction_strategy_class text,
		compression_parameters text,
		value_alias text,
		column_aliases text,
		compaction_strategy_options text,
		speculative_retry text,
		populate_io_cache_on_flush boolean,
		index_interval int,
		dropped_columns map<text, bigint>,
		PRIMARY KEY (keyspace_name, columnfamily_name)
	) WITH COMMENT='ColumnFamily definitions'

	CREATE TABLE schema_columns (
		keyspace_name text,
		columnfamily_name text,
		column_name text,
		validator text,
		index_type text,
		index_options text,
		index_name text,
		component_index int,
		type text,
		PRIMARY KEY(keyspace_name, columnfamily_name, column_name)
	) WITH COMMENT='ColumnFamily column attributes'

	CREATE TABLE schema_triggers (
		keyspace_name text,
		columnfamily_name text,
		trigger_name text,
		trigger_options map<text, text>,
		PRIMARY KEY (keyspace_name, columnfamily_name, trigger_name)
	) WITH COMMENT='triggers metadata table'

	CREATE TABLE schema_usertypes (
	    keyspace_name text,
		type_name text,
		column_names list<text>,
		column_types list<text>,
		PRIMARY KEY (keyspace_name, type_name)
	) WITH COMMENT='Defined user types'

    CREATE TABLE schema_functions (
		namespace text,
		name text,
		signature blob,
		argument_names list<text>,
		argument_types list<text>,
		return_type text,
		deterministic boolean,
		language text,
		body text,
		primary key ((namespace, name), signature)
	) WITH COMMENT='user defined functions' AND gc_grace_seconds=604800


    public static final CFMetaData SchemaAggregatesTable =
        compile(SCHEMA_AGGREGATES_TABLE, "user defined aggregate definitions",
                "CREATE TABLE %s ("
                + "keyspace_name text,"
                + "aggregate_name text,"
                + "signature blob,"
                + "argument_types list<text>,"
                + "return_type text,"
                + "state_func text,"
                + "state_type text,"
                + "final_func text,"
                + "initcond blob,"
                + "PRIMARY KEY ((keyspace_name), aggregate_name, signature))")
                .gcGraceSeconds(WEEK);

	CREATE TABLE "IndexInfo" (
		table_name text,
		index_name text,
		PRIMARY KEY (table_name, index_name)
	) WITH COMPACT STORAGE AND COMMENT='indexes that have been completed'

	CREATE TABLE "NodeIdInfo" (
		key text,
		id timeuuid,
		PRIMARY KEY (key, id)
	) WITH COMPACT STORAGE AND COMMENT='counter node IDs'

	CREATE TABLE hints (
		target_id uuid,
		hint_id timeuuid,
		message_version int,
		mutation blob,
		PRIMARY KEY (target_id, hint_id, message_version)
	) WITH COMPACT STORAGE 
	  AND COMPACTION={'class' : 'SizeTieredCompactionStrategy', 'enabled' : false} 
	  AND COMMENT='hints awaiting delivery'
	  AND gc_grace_seconds=0

	CREATE TABLE peers (
		peer inet PRIMARY KEY,
		data_center text,
		host_id uuid,
		preferred_ip inet,
		rack text,
		release_version text,
		rpc_address inet,
		schema_version uuid,
		tokens set<varchar>
	)

	CREATE TABLE peer_events (
		peer inet PRIMARY KEY,
		hints_dropped map<uuid, int>
	) WITH COMMENT='cf contains events related to peers'

	CREATE TABLE local (
		key text PRIMARY KEY,
		tokens set<varchar>,
		cluster_name text,
		gossip_generation int,
		bootstrapped text,
		host_id uuid,
		release_version text,
		thrift_version text,
		cql_version text,
		native_protocol_version text,
		data_center text,
		rack text,
		partitioner text,
		schema_version uuid,
		truncated_at map<uuid, blob>
	) WITH COMMENT='information about the local node'

	CREATE TABLE batchlog (
		id uuid PRIMARY KEY,
		written_at timestamp,
		data blob
	) WITH COMMENT='uncommited batches' AND gc_grace_seconds=0 
	  AND COMPACTION={'class' : 'SizeTieredCompactionStrategy', 'min_threshold' : 2}

	CREATE TABLE range_xfers (
		token_bytes blob PRIMARY KEY,
		requested_at timestamp
	) WITH COMMENT='ranges requested for transfer here'

	CREATE TABLE compactions_in_progress (
		id uuid PRIMARY KEY,
		keyspace_name text,
		columnfamily_name text,
		inputs set<int>
	) WITH COMMENT='unfinished compactions'

	CREATE TABLE paxos (
		row_key blob,
		cf_id UUID,
		in_progress_ballot timeuuid,
		proposal_ballot timeuuid,
		proposal blob,
		most_recent_commit_at timeuuid,
		most_recent_commit blob,
		PRIMARY KEY (row_key, cf_id)
	) WITH COMMENT='in-progress paxos proposals'

	CREATE TABLE sstable_activity (
		keyspace_name text,
		columnfamily_name text,
		generation int,
		rate_15m double,
		rate_120m double,
		PRIMARY KEY ((keyspace_name, columnfamily_name, generation))
	) WITH COMMENT='historic sstable read rates'

	CREATE TABLE compaction_history (
		id uuid,
		keyspace_name text,
		columnfamily_name text,
		compacted_at timestamp,
		bytes_in bigint,
		bytes_out bigint,
		rows_merged map<int, bigint>,
		PRIMARY KEY (id)
	) WITH COMMENT='show all compaction history' AND DEFAULT_TIME_TO_LIVE=604800

属于system_traces有2个:
====================================================================
	CREATE TABLE sessions (
		session_id uuid PRIMARY KEY,
		coordinator inet,
		request text,
		started_at timestamp,
		parameters map<text, text>,
		duration int
	) WITH COMMENT='traced sessions'

	CREATE TABLE events (
		session_id uuid,
		event_id timeuuid,
		source inet,
		thread text,
		activity text,
		source_elapsed int,
		PRIMARY KEY (session_id, event_id)
	)


属于system_auth有3个:
====================================================================
	CREATE TABLE system_auth.users (
		name text,
		super boolean,
		PRIMARY KEY(name)
	) WITH gc_grace_seconds=90 * 24 * 60 * 60 // 3 months(相当于有效期是3个月)

	默认的超级用户是cassandra密码也是cassandra


	CREATE TABLE system_auth.credentials (
		username text,
		salted_hash text, //使用BCrypt算法
		options map<text,text>, //这个字段目前未使用
		PRIMARY KEY(username)
	) WITH gc_grace_seconds=90 * 24 * 60 * 60 // 3 months


	CREATE TABLE system_auth.permissions (
		username text,
		resource text,
		permissions set<text>,
		PRIMARY KEY(username, resource)
	) WITH gc_grace_seconds=90 * 24 * 60 * 60 // 3 months

