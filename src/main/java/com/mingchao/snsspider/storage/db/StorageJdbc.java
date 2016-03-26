package com.mingchao.snsspider.storage.db;

import java.io.Serializable;
import java.math.BigInteger;
import java.util.List;

import org.hibernate.Session;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;

import com.mingchao.snsspider.storage.util.HibernateExeTask;
import com.mingchao.snsspider.storage.util.HibernateUtil;
import com.mingchao.snsspider.storage.util.SQLUtil;

public class StorageJdbc extends StorageDB {

	public StorageJdbc(HibernateUtil hu) {
		super(hu);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	@Override
	public List<Object> get(final Class<?> c, final Serializable idStart,
			final Serializable idEnd) {
		HibernateExeTask hs = new HibernateExeTask() {
			@Override
			public Object execute(Session session) {
				String fieldName = SQLUtil.getIdFieldName(c);
				log.debug(fieldName);
				return session.createCriteria(c)
						.add(Restrictions.ge(fieldName, idStart))
						.add(Restrictions.lt(fieldName, idEnd))
						.addOrder(Order.asc(fieldName)).list();
			}
		};
		return (List) hu.execute(hs);

	}

	// TODO
	@Override
	public void delete(final Class<?> c, final Serializable idStart,
			final Serializable idEnd) {
		HibernateExeTask hs = new HibernateExeTask() {
			@Override
			public Object execute(Session session) {
				String deleteSql = SQLUtil.deleteSql(c, idStart, idEnd);
				return session.createSQLQuery(deleteSql).executeUpdate();
			}
		};
		hu.execute(hs);
	}


	@Override
	public Boolean hasMore(final Class<?> c, final Serializable idStart) {
		HibernateExeTask hs = new HibernateExeTask() {
			@Override
			public Object execute(Session session) {
				String queryString = SQLUtil.hasMoreSql(c, idStart);
				BigInteger o = (BigInteger) session.createSQLQuery(queryString)
						.uniqueResult();
				log.debug("has more: " + o.getClass().getName() + "values: "
						+ o);
				return o.intValue() == 1;
			}
		};
		return (Boolean) hu.execute(hs);
	}
}
