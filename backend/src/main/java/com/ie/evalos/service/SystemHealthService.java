package com.ie.evalos.service;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.search.Search;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.CompositeHealthContributor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.NamedContributor;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.info.BuildProperties;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The Admin's System health page in one read (D82): the same health indicators and Micrometer meters
 * Actuator serves, plus four live database facts, shaped for a screen instead of for a scraper.
 *
 * <p><strong>No brand data, so no brand scope.</strong> Every figure is about this process and its
 * database server — memory, threads, the pool, request counts by route template — never a row a brand
 * owns. That is why it is ADMIN-only and why it is safe to show cross-brand.
 *
 * <p><strong>Counters are since the process started</strong>; the page turns two reads into a rate.
 * A meter that does not exist on this platform (load average on Windows, open files) is {@code null},
 * never a zero that would read as a measurement.
 */
@Service
public class SystemHealthService {

	public record Report(Instant at, Status status, Runtime runtime, Database database, Memory memory, Gc gc,
			Threads threads, Cpu cpu, Pool pool, Web web, Http http, Logs logs, Auth auth, List<Job> jobs) {
	}

	public record Status(String overall, String liveness, String readiness, List<Component> components) {
	}

	public record Component(String name, String status, Map<String, Object> details) {
	}

	public record Runtime(String application, String version, Instant builtAt, List<String> profiles,
			String javaVersion, String javaVendor, String os, int processors, long pid, Instant startedAt,
			long uptimeMs, String zone, Long loadedClasses) {
	}

	public record Database(boolean reachable, String error, Double roundTripMs, String serverVersion,
			Long sizeBytes, Map<String, Long> serverConnections, String schemaVersion, Instant schemaMigratedAt,
			Long failedMigrations, Long diskFreeBytes, Long diskTotalBytes) {
	}

	public record Memory(Double heapUsed, Double heapCommitted, Double heapMax, Double nonHeapUsed,
			Double nonHeapCommitted, List<MemoryPool> pools) {
	}

	public record MemoryPool(String name, String area, Double used, Double max) {
	}

	public record Gc(Long pauses, Double pauseTotalMs, Double pauseMaxMs, Double allocatedBytes,
			Double promotedBytes, Double liveDataBytes, Double maxDataBytes, Double overhead) {
	}

	public record Threads(Double live, Double daemon, Double peak, Map<String, Double> states) {
	}

	public record Cpu(Double process, Double system, Double processors, Double loadAverage) {
	}

	public record Pool(Double active, Double idle, Double pending, Double total, Double max, Double min,
			Double timeouts, Double acquireMeanMs, Double acquireMaxMs, Double usageMeanMs, Double usageMaxMs) {
	}

	public record Web(Double busyThreads, Double currentThreads, Double maxThreads) {
	}

	public record Http(long requests, Map<String, Long> byStatusClass, double meanMs, double maxMs,
			List<Route> routes) {
	}

	/** One route template. {@code p95Ms} is the worst of its status variants over Micrometer's ~2-minute window. */
	public record Route(String method, String uri, long count, double meanMs, double maxMs, Double p95Ms,
			long clientErrors, long serverErrors) {
	}

	public record Logs(Map<String, Double> byLevel) {
	}

	public record Auth(Double signInsOk, Double signInsRefused) {
	}

	public record Job(String job, double ok, double failed, double itemsFailed) {
	}

	private static final List<String> LOG_LEVELS = List.of("error", "warn", "info", "debug", "trace");

	private final MeterRegistry registry;
	private final HealthContributorRegistry health;
	private final ApplicationAvailability availability;
	private final JdbcTemplate jdbc;
	private final Environment environment;
	private final ObjectProvider<BuildProperties> build;

	SystemHealthService(MeterRegistry registry, HealthContributorRegistry health, ApplicationAvailability availability,
			JdbcTemplate jdbc, Environment environment, ObjectProvider<BuildProperties> build) {
		this.registry = registry;
		this.health = health;
		this.availability = availability;
		this.jdbc = jdbc;
		this.environment = environment;
		this.build = build;
	}

	public Report report() {
		List<Component> components = new ArrayList<>();
		collect("", health, components);
		components.sort(Comparator.comparing(Component::name));
		String overall = components.stream().anyMatch(c -> !"UP".equals(c.status())) ? "DOWN" : "UP";
		Status status = new Status(overall, availability.getLivenessState().toString(),
				availability.getReadinessState().toString(), components);

		return new Report(Instant.now(), status, runtime(), database(components), memory(), gc(), threads(), cpu(),
				pool(), web(), http(), logs(), auth(), jobs());
	}

	// --- health ---------------------------------------------------------------------------------------

	private void collect(String prefix, Iterable<NamedContributor<HealthContributor>> contributors, List<Component> out) {
		for (NamedContributor<HealthContributor> named : contributors) {
			String name = prefix + named.getName();
			if (named.getContributor() instanceof HealthIndicator indicator) {
				Health result = indicator.getHealth(true);
				out.add(new Component(name, result.getStatus().getCode(), scalars(result.getDetails())));
			}
			else if (named.getContributor() instanceof CompositeHealthContributor composite) {
				collect(name + ".", composite, out);
			}
		}
	}

	/** Numbers, strings and booleans only — a nested structure (certificate chains) is not a figure. */
	private static Map<String, Object> scalars(Map<String, Object> details) {
		Map<String, Object> out = new TreeMap<>();
		details.forEach((key, value) -> {
			if (value instanceof Number || value instanceof Boolean || value instanceof CharSequence) {
				out.put(key, value instanceof CharSequence ? value.toString() : value);
			}
		});
		return out;
	}

	// --- runtime and database -------------------------------------------------------------------------

	private Runtime runtime() {
		BuildProperties props = build.getIfAvailable();
		var bean = ManagementFactory.getRuntimeMXBean();
		String[] active = environment.getActiveProfiles();
		return new Runtime(environment.getProperty("spring.application.name", "evalos"),
				props == null ? null : props.getVersion(), props == null ? null : props.getTime(),
				Arrays.asList(active.length > 0 ? active : environment.getDefaultProfiles()),
				System.getProperty("java.version"), System.getProperty("java.vendor"),
				System.getProperty("os.name") + " " + System.getProperty("os.arch"),
				java.lang.Runtime.getRuntime().availableProcessors(), ProcessHandle.current().pid(),
				Instant.ofEpochMilli(bean.getStartTime()), bean.getUptime(), ZoneId.systemDefault().getId(),
				asLong(gauge(registry.find("jvm.classes.loaded"))));
	}

	/**
	 * Four reads against the server itself. Any failure is reported as unreachable with the exception's
	 * class only — a driver message can carry connection details.
	 */
	private Database database(List<Component> components) {
		Map<String, Object> disk = components.stream().filter(c -> c.name().equals("diskSpace"))
				.map(Component::details).findFirst().orElse(Map.of());
		Long diskFree = disk.get("free") instanceof Number n ? n.longValue() : null;
		Long diskTotal = disk.get("total") instanceof Number n ? n.longValue() : null;
		try {
			long started = System.nanoTime();
			jdbc.queryForObject("select 1", Integer.class);
			double roundTrip = (System.nanoTime() - started) / 1_000_000.0;
			String version = jdbc.queryForObject("show server_version", String.class);
			Long size = databaseSize();
			Map<String, Long> connections = new LinkedHashMap<>();
			jdbc.query("select coalesce(state, 'background') as state, count(*) as n from pg_stat_activity "
					+ "where datname = current_database() group by 1 order by 2 desc",
					rs -> {
						connections.put(rs.getString("state"), rs.getLong("n"));
					});
			Map<String, Object> schema = jdbc.queryForMap("select (select version from flyway_schema_history "
					+ "where success and version is not null order by installed_rank desc limit 1) as version, "
					+ "(select max(installed_on) from flyway_schema_history where success) as migrated_at, "
					+ "(select count(*) from flyway_schema_history where not success) as failed");
			return new Database(true, null, roundTrip, version, size, connections, (String) schema.get("version"),
					schema.get("migrated_at") instanceof java.sql.Timestamp t ? t.toInstant() : null,
					((Number) schema.get("failed")).longValue(), diskFree, diskTotal);
		}
		catch (RuntimeException unreachable) {
			return new Database(false, unreachable.getClass().getSimpleName(), null, null, null, Map.of(), null, null,
					null, diskFree, diskTotal);
		}
	}

	/** The last size read and when; {@code pg_database_size} walks every file of the database (~300 ms locally). */
	private volatile long[] sizeCache;

	/**
	 * The database's size, re-read at most once a minute. Every other figure here is a few milliseconds,
	 * and the page polls every five seconds — this one alone would make the read most of a second.
	 * ponytail: one racy volatile; two pollers in the same minute may both re-read, which is harmless.
	 */
	private Long databaseSize() {
		long[] cached = sizeCache;
		long now = System.currentTimeMillis();
		if (cached == null || now - cached[1] > 60_000) {
			Long size = jdbc.queryForObject("select pg_database_size(current_database())", Long.class);
			cached = new long[] { size == null ? -1 : size, now };
			sizeCache = cached;
		}
		return cached[0] < 0 ? null : cached[0];
	}

	// --- JVM ------------------------------------------------------------------------------------------

	private Memory memory() {
		List<MemoryPool> pools = registry.find("jvm.memory.used").gauges().stream()
				.map(g -> new MemoryPool(g.getId().getTag("id"), g.getId().getTag("area"), finite(g.value()),
						positive(gauge(registry.find("jvm.memory.max").tags("id", g.getId().getTag("id"),
								"area", g.getId().getTag("area"))))))
				.sorted(Comparator.comparing(MemoryPool::area).thenComparing(MemoryPool::name))
				.toList();
		return new Memory(gauge(registry.find("jvm.memory.used").tag("area", "heap")),
				gauge(registry.find("jvm.memory.committed").tag("area", "heap")),
				// Not the sum of the pools' maxima: several report -1 ("no limit of their own"). The heap's is -Xmx.
				positive((double) ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax()),
				gauge(registry.find("jvm.memory.used").tag("area", "nonheap")),
				gauge(registry.find("jvm.memory.committed").tag("area", "nonheap")), pools);
	}

	private Gc gc() {
		Collection<Timer> pauses = registry.find("jvm.gc.pause").timers();
		return new Gc(pauses.stream().mapToLong(Timer::count).sum(),
				pauses.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum(),
				pauses.stream().mapToDouble(t -> t.max(TimeUnit.MILLISECONDS)).max().orElse(0),
				count(registry.find("jvm.gc.memory.allocated")), count(registry.find("jvm.gc.memory.promoted")),
				gauge(registry.find("jvm.gc.live.data.size")), gauge(registry.find("jvm.gc.max.data.size")),
				gauge(registry.find("jvm.gc.overhead")));
	}

	private Threads threads() {
		Map<String, Double> states = new TreeMap<>();
		registry.find("jvm.threads.states").gauges()
				.forEach(g -> states.put(g.getId().getTag("state"), finite(g.value())));
		return new Threads(gauge(registry.find("jvm.threads.live")), gauge(registry.find("jvm.threads.daemon")),
				gauge(registry.find("jvm.threads.peak")), states);
	}

	private Cpu cpu() {
		return new Cpu(gauge(registry.find("process.cpu.usage")), gauge(registry.find("system.cpu.usage")),
				gauge(registry.find("system.cpu.count")), positive(gauge(registry.find("system.load.average.1m"))));
	}

	private Pool pool() {
		Timer acquire = registry.find("hikaricp.connections.acquire").timer();
		Timer usage = registry.find("hikaricp.connections.usage").timer();
		return new Pool(gauge(registry.find("hikaricp.connections.active")),
				gauge(registry.find("hikaricp.connections.idle")), gauge(registry.find("hikaricp.connections.pending")),
				gauge(registry.find("hikaricp.connections")), gauge(registry.find("hikaricp.connections.max")),
				gauge(registry.find("hikaricp.connections.min")), count(registry.find("hikaricp.connections.timeout")),
				acquire == null ? null : finite(acquire.mean(TimeUnit.MILLISECONDS)),
				acquire == null ? null : finite(acquire.max(TimeUnit.MILLISECONDS)),
				usage == null ? null : finite(usage.mean(TimeUnit.MILLISECONDS)),
				usage == null ? null : finite(usage.max(TimeUnit.MILLISECONDS)));
	}

	private Web web() {
		return new Web(gauge(registry.find("tomcat.threads.busy")), gauge(registry.find("tomcat.threads.current")),
				gauge(registry.find("tomcat.threads.config.max")));
	}

	// --- traffic --------------------------------------------------------------------------------------

	private Http http() {
		Collection<Timer> timers = registry.find("http.server.requests").timers();
		Map<String, Long> byClass = new TreeMap<>();
		Map<String, List<Timer>> byRoute = new TreeMap<>();
		long requests = 0;
		double totalMs = 0;
		double maxMs = 0;
		for (Timer timer : timers) {
			requests += timer.count();
			totalMs += timer.totalTime(TimeUnit.MILLISECONDS);
			maxMs = Math.max(maxMs, timer.max(TimeUnit.MILLISECONDS));
			byClass.merge(statusClass(timer.getId().getTag("status")), timer.count(), Long::sum);
			byRoute.computeIfAbsent(timer.getId().getTag("method") + " " + timer.getId().getTag("uri"),
					k -> new ArrayList<>()).add(timer);
		}
		List<Route> routes = byRoute.values().stream().map(this::route)
				.sorted(Comparator.comparingLong(Route::count).reversed()).limit(25).toList();
		return new Http(requests, byClass, requests == 0 ? 0 : totalMs / requests, maxMs, routes);
	}

	private Route route(List<Timer> variants) {
		var id = variants.get(0).getId();
		long count = variants.stream().mapToLong(Timer::count).sum();
		double total = variants.stream().mapToDouble(t -> t.totalTime(TimeUnit.MILLISECONDS)).sum();
		double max = variants.stream().mapToDouble(t -> t.max(TimeUnit.MILLISECONDS)).max().orElse(0);
		long client = variants.stream().filter(t -> statusClass(t.getId().getTag("status")).equals("4xx"))
				.mapToLong(Timer::count).sum();
		long server = variants.stream().filter(t -> statusClass(t.getId().getTag("status")).equals("5xx"))
				.mapToLong(Timer::count).sum();
		// The percentile gauges are reported in the registry's base unit; seconds for every registry Boot ships.
		Double p95 = registry.find("http.server.requests.percentile")
				.tags("uri", id.getTag("uri"), "method", id.getTag("method"), "phi", "0.95").gauges().stream()
				// The window decays to 0, not to "no value": a route not called lately has no p95, not a 0 ms one.
				.mapToDouble(Gauge::value).filter(v -> Double.isFinite(v) && v > 0).max().stream().map(seconds -> seconds * 1000)
				.boxed().findFirst().orElse(null);
		return new Route(id.getTag("method"), id.getTag("uri"), count, count == 0 ? 0 : total / count, max, p95,
				client, server);
	}

	private static String statusClass(String status) {
		return status == null || status.isEmpty() || !Character.isDigit(status.charAt(0)) ? "other"
				: status.charAt(0) + "xx";
	}

	private Logs logs() {
		Map<String, Double> byLevel = new LinkedHashMap<>();
		for (String level : LOG_LEVELS) {
			Double value = count(registry.find("logback.events").tag("level", level));
			if (value != null) {
				byLevel.put(level, value);
			}
		}
		return new Logs(byLevel);
	}

	private Auth auth() {
		return new Auth(count(registry.find("evalos.auth.login").tag("outcome", "ok")),
				count(registry.find("evalos.auth.login").tag("outcome", "refused")));
	}

	private List<Job> jobs() {
		Map<String, double[]> byJob = new TreeMap<>();
		registry.find("evalos.jobs.runs").counters().forEach(c -> byJob
				.computeIfAbsent(c.getId().getTag("job"), k -> new double[3])[
				"ok".equals(c.getId().getTag("outcome")) ? 0 : 1] += c.count());
		registry.find("evalos.jobs.items.failed").counters()
				.forEach(c -> byJob.computeIfAbsent(c.getId().getTag("job"), k -> new double[3])[2] += c.count());
		return byJob.entrySet().stream().map(e -> new Job(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2]))
				.toList();
	}

	// --- meter helpers --------------------------------------------------------------------------------

	/** Sum of the matching gauges, or null when none exists here. */
	private static Double gauge(Search search) {
		Collection<Gauge> gauges = search.gauges();
		return gauges.isEmpty() ? null : finite(gauges.stream().mapToDouble(Gauge::value).filter(Double::isFinite).sum());
	}

	/** Sum of the matching counters (plain or function), or null when none exists here. */
	private static Double count(Search search) {
		Collection<Counter> counters = search.counters();
		Collection<FunctionCounter> functions = search.functionCounters();
		if (counters.isEmpty() && functions.isEmpty()) {
			return null;
		}
		return counters.stream().mapToDouble(Counter::count).sum()
				+ functions.stream().mapToDouble(FunctionCounter::count).sum();
	}

	/** NaN and infinity become null: they are "not measured", and JSON has no word for them. */
	private static Double finite(double value) {
		return Double.isFinite(value) ? value : null;
	}

	/** -1 is how the JVM says "no limit" or "not on this platform". */
	private static Double positive(Double value) {
		return value == null || value < 0 ? null : value;
	}

	private static Long asLong(Double value) {
		return value == null ? null : value.longValue();
	}
}
