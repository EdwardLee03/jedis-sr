package redis.clients.jedis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import redis.clients.jedis.exceptions.JedisAskDataException;
import redis.clients.jedis.exceptions.JedisClusterException;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.exceptions.JedisMovedDataException;
import redis.clients.util.RedisInputStream;
import redis.clients.util.RedisOutputStream;
import redis.clients.util.SafeEncoder;

/**
 * "Redis协议"实现类。
 */
public final class Protocol {

	private static final String ASK_RESPONSE = "ASK";
	private static final String MOVED_RESPONSE = "MOVED";
	private static final String CLUSTERDOWN_RESPONSE = "CLUSTERDOWN";

	/** 默认的Redis服务端口 */
	public static final int DEFAULT_PORT = 6379;
	/** 默认的Sentinel服务端口 */
	public static final int DEFAULT_SENTINEL_PORT = 26379;
	/** 默认的超时时间 */
	public static final int DEFAULT_TIMEOUT = 2000;
	/** 默认的数据库索引 */
	public static final int DEFAULT_DATABASE = 0;

	/** 内容编码 */
	public static final String CHARSET = "UTF-8";

	/** 请求响应内容类型标识 */
	public static final byte DOLLAR_BYTE = '$';
	public static final byte ASTERISK_BYTE = '*'; // "批量操作对象列表"返回
	public static final byte PLUS_BYTE = '+'; // 状态码
	public static final byte MINUS_BYTE = '-'; // 出现错误
	public static final byte COLON_BYTE = ':'; // "整型类型"返回

	/*
	 * 监控(Sentinel)服务
	 */
	/** "主库(Master)"列表 */
	public static final String SENTINEL_MASTERS = "masters";
	public static final String SENTINEL_GET_MASTER_ADDR_BY_NAME = "get-master-addr-by-name";
	/** 重置 */
	public static final String SENTINEL_RESET = "reset";
	/** "从库(Slave)"列表 */
	public static final String SENTINEL_SLAVES = "slaves";
	/** "故障转移"操作 */
	public static final String SENTINEL_FAILOVER = "failover";
	/** "监视"操作 */
	public static final String SENTINEL_MONITOR = "monitor";
	/** "移除主库"操作 */
	public static final String SENTINEL_REMOVE = "remove";
	/** "将从库设置为主库"操作 */
	public static final String SENTINEL_SET = "set";

	/*
	 * 集群(Cluster)服务
	 */
	/** Redis节点列表 */
	public static final String CLUSTER_NODES = "nodes";
	/** 心跳健康检测 */
	public static final String CLUSTER_MEET = "meet";
	public static final String CLUSTER_RESET = "reset";
	public static final String CLUSTER_ADDSLOTS = "addslots";
	public static final String CLUSTER_DELSLOTS = "delslots";
	public static final String CLUSTER_INFO = "info";
	public static final String CLUSTER_GETKEYSINSLOT = "getkeysinslot";
	public static final String CLUSTER_SETSLOT = "setslot";
	public static final String CLUSTER_SETSLOT_NODE = "node";
	/** 数据迁移 */
	public static final String CLUSTER_SETSLOT_MIGRATING = "migrating";
	public static final String CLUSTER_SETSLOT_IMPORTING = "importing";
	public static final String CLUSTER_SETSLOT_STABLE = "stable";
	public static final String CLUSTER_FORGET = "forget";
	public static final String CLUSTER_FLUSHSLOT = "flushslots";
	public static final String CLUSTER_KEYSLOT = "keyslot";
	public static final String CLUSTER_COUNTKEYINSLOT = "countkeysinslot";
	public static final String CLUSTER_SAVECONFIG = "saveconfig";
	/** "复制"操作 */
	public static final String CLUSTER_REPLICATE = "replicate";
	/** "从库"节点列表 */
	public static final String CLUSTER_SLAVES = "slaves";
	/** "故障转移"操作 */
	public static final String CLUSTER_FAILOVER = "failover";
	public static final String CLUSTER_SLOTS = "slots";

	/*
	 * "发布/订阅"服务
	 */
	public static final String PUBSUB_CHANNELS = "channels";
	public static final String PUBSUB_NUMSUB = "numsub";
	public static final String PUBSUB_NUM_PAT = "numpat";

	private Protocol() {
		// this prevent the class from instantiation
	}

	/**
	 * 发送Redis命令到服务端。
	 *
	 * @param os
	 *            输出流
	 * @param command
	 *            Redis命令
	 * @param args
	 *            二进制格式的命令参数列表
	 */
	public static void sendCommand(RedisOutputStream os, Command command,
			byte[]... args) {
		sendCommand(os, command.raw, args);
	}

	private static void sendCommand(RedisOutputStream os, byte[] command,
			byte[]... args) {
		try {
			// 构造"Redis二进制协议"格式内容
			os.write(ASTERISK_BYTE);
			os.writeIntCrLf(args.length + 1);
			os.write(DOLLAR_BYTE);
			os.writeIntCrLf(command.length);
			os.write(command);
			os.writeCrLf();

			for (byte[] arg : args) {
				os.write(DOLLAR_BYTE);
				os.writeIntCrLf(arg.length);
				os.write(arg);
				os.writeCrLf();
			}
		} catch (IOException e) {
			// 抛出"Redis连接异常"
			throw new JedisConnectionException(e);
		}
	}

	/**
	 * 处理Redis集群重定向"请求错误"响应。
	 *
	 * @param is
	 */
	private static void processError(RedisInputStream is) {
		String message = is.readLine();
		// TODO: I'm not sure if this is the best way to do this.
		// Maybe Read only first 5 bytes instead?
		if (message.startsWith(MOVED_RESPONSE)) {
			String[] movedInfo = parseTargetHostAndSlot(message);
			throw new JedisMovedDataException(message, new HostAndPort(
					movedInfo[1], Integer.valueOf(movedInfo[2])),
					Integer.valueOf(movedInfo[0]));
		} else if (message.startsWith(ASK_RESPONSE)) {
			String[] askInfo = parseTargetHostAndSlot(message);
			throw new JedisAskDataException(message, new HostAndPort(
					askInfo[1], Integer.valueOf(askInfo[2])),
					Integer.valueOf(askInfo[0]));
		} else if (message.startsWith(CLUSTERDOWN_RESPONSE)) {
			throw new JedisClusterException(message);
		}
		throw new JedisDataException(message);
	}

	/**
	 * 解析目标主机地址和槽索引。
	 *
	 * @param clusterRedirectResponse
	 *            集群重定向响应信息
	 * @return
	 */
	private static String[] parseTargetHostAndSlot(
			String clusterRedirectResponse) {
		String[] response = new String[3];
		String[] messageInfo = clusterRedirectResponse.split(" ");
		String[] targetHostAndPort = messageInfo[2].split(":");
		response[0] = messageInfo[1];
		response[1] = targetHostAndPort[0];
		response[2] = targetHostAndPort[1];
		return response;
	}

	/**
	 * 处理Redis命令执行的响应信息。
	 *
	 * @param is
	 * @return
	 */
	private static Object process(RedisInputStream is) {
		try {
			byte b = is.readByte();
			if (b == MINUS_BYTE) {
				processError(is);
			} else if (b == ASTERISK_BYTE) {
				return processMultiBulkReply(is);
			} else if (b == COLON_BYTE) {
				return processInteger(is);
			} else if (b == DOLLAR_BYTE) {
				return processBulkReply(is);
			} else if (b == PLUS_BYTE) {
				return processStatusCodeReply(is);
			} else {
				throw new JedisConnectionException("Unknown reply: " + (char) b);
			}
		} catch (IOException e) {
			// 抛出"Redis连接异常"
			throw new JedisConnectionException(e);
		}
		// 可能返回 NULL
		return null;
	}

	/*
	 * 处理请求响应的状态码。
	 */
	private static byte[] processStatusCodeReply(RedisInputStream is) {
		return SafeEncoder.encode(is.readLine());
	}

	/*
	 * 处理一条命令的响应内容。
	 */
	private static byte[] processBulkReply(RedisInputStream is) {
		int len = Integer.parseInt(is.readLine());
		if (len == -1) {
			return null;
		}
		byte[] read = new byte[len];
		
		int offset = 0;
		try {
			while (offset < len) {
				int size = is.read(read, offset, (len - offset));
				if (size == -1)
					throw new JedisConnectionException(
							"It seems like server has closed the connection.");
				offset += size;
			}
			// read 2 more bytes for the command delimiter (命令分隔符)
			is.readByte();
			is.readByte();
		} catch (IOException e) {
			throw new JedisConnectionException(e);
		}

		return read;
	}

	/*
	 * 处理整数值。
	 */
	private static Long processInteger(RedisInputStream is) {
		String num = is.readLine();
		return Long.valueOf(num);
	}

	/*
	 * 处理多条命令的批量响应内容。
	 */
	private static List<Object> processMultiBulkReply(RedisInputStream is) {
		int num = Integer.parseInt(is.readLine());
		if (num == -1) {
			return null;
		}
		List<Object> ret = new ArrayList<Object>(num);
		for (int i = 0; i < num; i++) {
			try {
				ret.add(process(is)); // 递归地解析命令的响应内容
			} catch (JedisDataException e) {
				// Bug 怎么把异常返回的数据也添加到返回信息里去了？？？
				ret.add(e);
			}
		}
		return ret;
	}

	/**
	 * 读取Redis命令执行的响应信息。
	 * 
	 * @param is
	 * @return
	 */
	public static Object read(RedisInputStream is) {
		return process(is);
	}

	/**
	 * 转换布尔类型值为字节数组。
	 * 
	 * @param value
	 * @return
	 */
	public static final byte[] toByteArray(boolean value) {
		return toByteArray(value ? 1 : 0);
	}

	/**
	 * 转换整型值为字节数组。
	 * 
	 * @param value
	 * @return
	 */
	public static final byte[] toByteArray(int value) {
		return SafeEncoder.encode(String.valueOf(value));
	}

	/**
	 * 转换长整型值为字节数组。
	 * 
	 * @param value
	 * @return
	 */
	public static final byte[] toByteArray(long value) {
		return SafeEncoder.encode(String.valueOf(value));
	}

	/**
	 * 转换双精度类型值为字节数组。
	 * 
	 * @param value
	 * @return
	 */
	public static final byte[] toByteArray(double value) {
		return SafeEncoder.encode(String.valueOf(value));
	}

	/**
	 * "Redis命令集"枚举表示
	 */
	public static enum Command {
		PING, SET, GET, QUIT, EXISTS, DEL, TYPE, FLUSHDB, KEYS, RANDOMKEY, RENAME, RENAMENX, RENAMEX, DBSIZE, EXPIRE, EXPIREAT, TTL, SELECT, MOVE, FLUSHALL, GETSET, MGET, SETNX, SETEX, MSET, MSETNX, DECRBY, DECR, INCRBY, INCR, APPEND, SUBSTR,
		// hash
		HSET, HGET, HSETNX, HMSET, HMGET, HINCRBY, HEXISTS, HDEL, HLEN, HKEYS, HVALS, HGETALL,
		// list
		RPUSH, LPUSH, LLEN, LRANGE, LTRIM, LINDEX, LSET, LREM, LPOP, RPOP, RPOPLPUSH,
		// set
		SADD, SMEMBERS, SREM, SPOP, SMOVE, SCARD, SISMEMBER, SINTER, SINTERSTORE, SUNION, SUNIONSTORE, SDIFF, SDIFFSTORE, SRANDMEMBER,
		// sorted set
		ZADD, ZRANGE, ZREM, ZINCRBY, ZRANK, ZREVRANK, ZREVRANGE, ZCARD, ZSCORE,
		// transaction
		MULTI, DISCARD, EXEC, WATCH, UNWATCH, SORT, BLPOP, BRPOP, AUTH, SUBSCRIBE, PUBLISH, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE, PUBSUB, ZCOUNT, ZRANGEBYSCORE, ZREVRANGEBYSCORE, ZREMRANGEBYRANK, ZREMRANGEBYSCORE, ZUNIONSTORE, ZINTERSTORE, ZLEXCOUNT, ZRANGEBYLEX, ZREMRANGEBYLEX, SAVE, BGSAVE, BGREWRITEAOF, LASTSAVE, SHUTDOWN, INFO, MONITOR, SLAVEOF, CONFIG, STRLEN, SYNC, LPUSHX, PERSIST, RPUSHX, ECHO, LINSERT, DEBUG, BRPOPLPUSH, SETBIT, GETBIT, BITPOS, SETRANGE, GETRANGE, EVAL, EVALSHA, SCRIPT, SLOWLOG, OBJECT, BITCOUNT, BITOP, SENTINEL, DUMP, RESTORE, PEXPIRE, PEXPIREAT, PTTL, INCRBYFLOAT, PSETEX, CLIENT, TIME, MIGRATE, HINCRBYFLOAT, SCAN, HSCAN, SSCAN, ZSCAN, WAIT, CLUSTER, ASKING, PFADD, PFCOUNT, PFMERGE;

		public final byte[] raw;

		private Command() {
			raw = SafeEncoder.encode(this.name());
		}
	}

	/**
	 * "Redis关键字"枚举表示
	 */
	public static enum Keyword {
		AGGREGATE, ALPHA, ASC, BY, DESC, GET, LIMIT, MESSAGE, NO, NOSORT, PMESSAGE, PSUBSCRIBE, PUNSUBSCRIBE, OK, ONE, QUEUED, SET, STORE, SUBSCRIBE, UNSUBSCRIBE, WEIGHTS, WITHSCORES, RESETSTAT, RESET, FLUSH, EXISTS, LOAD, KILL, LEN, REFCOUNT, ENCODING, IDLETIME, AND, OR, XOR, NOT, GETNAME, SETNAME, LIST, MATCH, COUNT;

		public final byte[] raw;

		private Keyword() {
			raw = SafeEncoder.encode(this.name().toLowerCase());
		}
	}

}
