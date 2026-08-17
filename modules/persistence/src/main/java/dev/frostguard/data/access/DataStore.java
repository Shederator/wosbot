package dev.frostguard.data.access;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.Persistence;
import jakarta.persistence.TypedQuery;
import dev.frostguard.api.runtime.WorkspacePaths;

/**
 * Centralized transactional gateway for all Frostguard persistence operations.
 * Provides reusable transaction helpers that eliminate boilerplate
 * open/commit/rollback/close sequences throughout the data layer.
 */
public final class DataStore implements AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(DataStore.class);
	private static final String PERSISTENCE_UNIT = "frostguardPU";
	private final EntityManagerFactory emf;

	private static volatile DataStore instance;

	private DataStore(Map<String, Object> overrides) {
		try {
			Map<String, Object> effectiveOverrides = new HashMap<>(overrides);
			effectiveOverrides.putIfAbsent("jakarta.persistence.jdbc.url",
					"jdbc:sqlite:" + WorkspacePaths.current().database() + "?busy_timeout=1000&journal_mode=WAL");
			this.emf = Persistence.createEntityManagerFactory(PERSISTENCE_UNIT, effectiveOverrides);
			DataSeeder.populate(this);
			LOG.info("Frostguard data store initialized");
		} catch (Exception cause) {
			LOG.error("Data store initialization failed: {}", cause.getMessage(), cause);
			throw new ExceptionInInitializerError(cause);
		}
	}

	public static DataStore openIsolated(Map<String, Object> overrides) {
		return new DataStore(overrides == null ? Map.of() : overrides);
	}

	public static DataStore getInstance() {
		DataStore current = instance;
		if (current != null) {
			return current;
		}
		synchronized (DataStore.class) {
			if (instance == null) {
				instance = new DataStore(Map.of());
			}
			return instance;
		}
	}

	public static void closeIfInitialized() {
		DataStore current = instance;
		if (current != null) {
			current.close();
		}
	}

	/**
	 * Executes a read-write operation inside a managed transaction.
	 * Commits on success, rolls back on failure, and always closes the session.
	 */
	public <R> R withinTransaction(Function<EntityManager, R> work) {
		EntityManager em = acquireSession();
		EntityTransaction tx = em.getTransaction();
		try {
			tx.begin();
			R result = work.apply(em);
			tx.commit();
			return result;
		} catch (Exception failure) {
			safeRollback(tx);
			LOG.error("Transaction failed: {}", failure.getMessage());
			throw failure;
		} finally {
			em.close();
		}
	}

	public void runInTransaction(Consumer<EntityManager> work) {
		withinTransaction(em -> {
			work.accept(em);
			return null;
		});
	}

	public <R> R readOnly(Function<EntityManager, R> reader) {
		EntityManager em = acquireSession();
		try {
			return reader.apply(em);
		} finally {
			em.close();
		}
	}

	public boolean persist(Object record) {
		try {
			withinTransaction(em -> { em.persist(record); return true; });
			return true;
		} catch (Exception ex) {
			LOG.error("Persist failed: {}", ex.getMessage());
			return false;
		}
	}

	public boolean merge(Object record) {
		try {
			withinTransaction(em -> em.merge(record));
			return true;
		} catch (Exception ex) {
			LOG.error("Merge failed: {}", ex.getMessage());
			return false;
		}
	}

	public boolean remove(Object record) {
		try {
			withinTransaction(em -> {
				Object attached = em.contains(record) ? record : em.merge(record);
				em.remove(attached);
				return null;
			});
			return true;
		} catch (Exception ex) {
			LOG.error("Remove failed: {}", ex.getMessage());
			return false;
		}
	}

	public <T> T lookup(Class<T> type, Object primaryKey) {
		return readOnly(em -> em.find(type, primaryKey));
	}

	public <T> List<T> executeQuery(String jpql, Class<T> resultType, Map<String, Object> params) {
		return readOnly(em -> {
			TypedQuery<T> query = em.createQuery(jpql, resultType);
			if (params != null) {
				params.forEach(query::setParameter);
			}
			return query.getResultList();
		});
	}

	@Override
	public void close() {
		if (emf != null && emf.isOpen()) {
			emf.close();
			LOG.info("Frostguard data store closed");
		}
	}

	@Deprecated
	public void shutdown() { close(); }

	private EntityManager acquireSession() {
		return emf.createEntityManager();
	}

	private static void safeRollback(EntityTransaction tx) {
		try {
			if (tx != null && tx.isActive()) {
				tx.rollback();
			}
		} catch (Exception rollbackError) {
			LOG.warn("Rollback failed: {}", rollbackError.getMessage());
		}
	}
}
