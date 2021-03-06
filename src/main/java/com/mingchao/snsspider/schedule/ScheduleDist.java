package com.mingchao.snsspider.schedule;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.Session;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnel;
import com.mingchao.snsspider.model.IdAble;
import com.mingchao.snsspider.model.QueueStatus;
import com.mingchao.snsspider.storage.db.StorageMySQL;
import com.mingchao.snsspider.storage.util.HibernateExeTask;
import com.mingchao.snsspider.storage.util.SQLUtil;
import com.mingchao.snsspider.util.BloomFilterUtil;

/**
 * 分布式调度器
 * 
 * @author yangchaojun
 *
 * @param <T>
 *            需要调度的结构
 */
public class ScheduleDist<T extends IdAble> extends ScheduleAdaptor<T> {
	protected static int STEP = 1000;

	private StorageMySQL storageSlave;
	private StorageMySQL storageMaster;
	private Queue<T> queue;
	private String entryTable;
	private Class<? extends QueueStatus> queueStatusClass;
	@SuppressWarnings("unused")
	private String queueStatusTable;

	public ScheduleDist(Class<T> entryClass,
			Class<? extends QueueStatus> queueStatusClass, Funnel<T> funnel) {
		this(entryClass, queueStatusClass, funnel,
				BloomFilterUtil.EXPECTEDENTRIES);
	}

	public ScheduleDist(Class<T> entryClass,
			Class<? extends QueueStatus> queueStatusClass, Funnel<T> funnel,
			long expectedEntries) {
		this(entryClass, queueStatusClass, funnel, expectedEntries,
				BloomFilterUtil.DEFAULT_FPP);
	}

	public ScheduleDist(Class<T> entryClass,
			Class<? extends QueueStatus> queueStatusClass, Funnel<T> funnel,
			long expectedEntries, double fpp) {
		this(entryClass, queueStatusClass, funnel, expectedEntries, fpp, null);
	}

	public ScheduleDist(Class<T> entryClass,
			Class<? extends QueueStatus> queueStatusClass, Funnel<T> funnel,
			long expectedEntries, double fpp, String bloomPath) {
		queue = new ConcurrentLinkedQueue<T>();
		this.entryClass = entryClass;
		this.entryTable = SQLUtil.getTableName(entryClass);
		this.queueStatusClass = queueStatusClass;
		this.queueStatusTable = SQLUtil.getTableName(queueStatusClass);
		this.bloomPath = bloomPath;
		this.filter = readFrom(funnel);
		this.filter = filter == null ? BloomFilter.create(funnel,
				expectedEntries, fpp) : filter;
	}
	
	public void setStorageSlave(StorageMySQL storageSlave) {
		this.storageSlave = storageSlave;
	}

	public void setStorageMaster(StorageMySQL storageMaster) {
		this.storageMaster = storageMaster;
	}

	@Override
	public boolean containsKey(T e) {
		return filter.mightContain(e);
	}

	// 调度统一放到Master数据库，数据库本身需要做去重
	@Override
	public void schadule(List<T> list) {
		List<T> list2 = new ArrayList<T>();
		for (T e : list) {
			if (!filter.mightContain(e)) {
				filter.put(e);
				list2.add(e);
			}
		}
		storageMaster.insertIgnore(list2);
	}

	// 调度统一放到Master数据库，数据库本身需要做去重
	@Override
	public void schadule(T e) {
		if (!filter.mightContain(e)) {
			filter.put(e);
			storageMaster.insertIgnore(e);
		}
	}

	// 调度统一放到Master数据库，数据库本身需要做去重，需要处理id，不处理filter
	@Override
	public void reschadule(T e) {
		storageMaster.insertDuplicateAutoId(e);
	}

	@SuppressWarnings("unchecked")
	@Override
	public T fetch() {// 单线程取
		if (closing) {
			return null;
		}
		log.debug(entryClass.getSimpleName() + " size: " + queue.size());
		T e = null;
		QueueStatus queueStatus = null;
		if (queue.isEmpty()) {
			queueStatus = (QueueStatus) storageSlave.get(queueStatusClass,
					entryTable);// 获取进度
			if (queueStatus == null) {
				try {
					queueStatus = queueStatusClass.newInstance();
				} catch (InstantiationException | IllegalAccessException e1) {// 不会出现该错误
					log.warn(e1, e1);
				}
				queueStatus.setTableName(entryTable);
				queueStatus.setIdEnd(1L);
				storageSlave.insertDuplicate(queueStatus);
			}
		}
		while (queue.isEmpty()) {
			Long idStart = queueStatus.getIdEnd();// 小于idStart的可以删除
			Long idEnd = idStart + STEP;
			queueStatus.setIdStart(idStart);
			List<Object> list = storageSlave.get(entryClass, idStart, idEnd);
			log.debug("idStart: " + idStart);
			if (list == null || list.isEmpty()) {
				log.debug(entryClass + " between " + idStart + " and " + idEnd
						+ " is null");
				if (storageSlave.hasMore(entryClass, idEnd)) {
					log.debug("has more id: " + idEnd);
					queueStatus.setIdEnd(idEnd);
					continue;
				}
				if (tryGetFromMaster()) {
					continue;
				} else {
					break;
				}
			} else {
				for (Object newEntry : list) {
					queue.offer((T) newEntry);
				}
				idEnd = ((IdAble) list.get(list.size() - 1)).getId() + 1;
				queueStatus.setIdEnd(idEnd);
				storageSlave.insertDuplicate(queueStatus);// 更新本地进度
			}
		}
		e = (T) queue.poll();
		return e;
	}

	@SuppressWarnings({ "unchecked" })
	private boolean tryGetFromMaster() {
		HibernateExeTask hs = new HibernateExeTask() {
			@Override
			public Object execute(Session session){
				// 锁定一行
				QueueStatus queueStatus = (QueueStatus) session.get(
						queueStatusClass, entryTable, new LockOptions(
								LockMode.PESSIMISTIC_WRITE));
				if (queueStatus == null) {
					try {
						queueStatus = queueStatusClass.newInstance();
					} catch (InstantiationException | IllegalAccessException e1) {// 不会出现该错误
						log.warn(e1, e1);
					}
					queueStatus.setTableName(entryTable);
					queueStatus.setIdEnd(1L);
					String sql = SQLUtil.getInsertDuplicateSql(queueStatus);
					session.createSQLQuery(sql).executeUpdate();
					return false;
				}
				while (true) {
					Long idStart = queueStatus.getIdEnd();
					Long idEnd = idStart + STEP;
					queueStatus.setIdStart(idStart);

					// 获取部分队列
					String fieldName = SQLUtil.getIdFieldName(entryClass);
					List<Object> list = session.createCriteria(entryClass)
							.add(Restrictions.ge(fieldName, idStart))
							.add(Restrictions.lt(fieldName, idEnd))
							.addOrder(Order.asc(fieldName)).list();

					// 如果队列为空
					if (list == null || list.isEmpty()) {
						String idField = SQLUtil.getIdFieldName(entryClass);
						List<?> rs = session.createCriteria(entryClass)
								.add(Restrictions.ge(idField, idEnd))
								.setMaxResults(1).list();
						if(rs.isEmpty()){
							return false;
						}else{// 如果有更多元素，继续
							queueStatus.setIdEnd(idEnd);
							continue;
						}
						
					} else {
						// 将队列保存到本地数据库
						storageSlave.insertIgnore(list);
						idEnd = ((IdAble) list.get(list.size() - 1)).getId() + 1;
						// 更新Master的进度信息
						queueStatus.setIdEnd(idEnd);
						String sql = SQLUtil.getInsertDuplicateSql(queueStatus);
						session.createSQLQuery(sql).executeUpdate();
						return true;
					}
				}
			}
		};
		return (boolean) storageMaster.execute(hs);
	}
	
	@Override
	public void closing(){
		log.info(this.getClass() + " for " + entryClass + "is closing");
	}

	// 关闭前需要更改idEnd游标为队列最前坐标
	@Override
	public void dump() {
		super.dump();
		T e = queue.peek();
		if (e != null) {
			Long endId = e.getId();
			QueueStatus queueStatus;
			try {
				queueStatus = queueStatusClass.newInstance();
				queueStatus.setTableName(entryTable);
				queueStatus.setIdEnd(endId);
				storageSlave.insertDuplicate(queueStatus);
			} catch (InstantiationException | IllegalAccessException e1) {// 不会出现该错误
				log.warn(e1, e1);
			}
		}
	}

	@Override
	public void close() {
		storageMaster.close();
		storageSlave.close();
	}
}
