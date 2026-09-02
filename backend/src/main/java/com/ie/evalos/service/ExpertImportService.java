package com.ie.evalos.service;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.ie.evalos.common.InvalidRequestException;
import com.ie.evalos.domain.AffiliationType;
import com.ie.evalos.domain.AuditAction;
import com.ie.evalos.domain.Availability;
import com.ie.evalos.domain.Expert;
import com.ie.evalos.domain.ExpertTier;
import com.ie.evalos.domain.FieldTag;
import com.ie.evalos.domain.LetterType;
import com.ie.evalos.domain.VisaCategory;
import com.ie.evalos.repository.ExpertRepository;
import com.ie.evalos.security.TenantContext;
import com.ie.evalos.service.ExpertService.ExpertForm;
import com.ie.evalos.service.ExpertService.ExpertSnapshot;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * The bulk sheet upload: the ENM's primary maintenance path, not a convenience.
 *
 * <p>Their roster lives in a spreadsheet today, so fifty experts arrive as a file and
 * one arrives as a form. Both end up in the same place — the sheet's columns are mapped
 * onto {@link ExpertForm}, and the same Bean Validation constraints that guard the form
 * produce this report's rows. There is one definition of "a valid expert".
 *
 * <p><strong>All-or-nothing, in one transaction.</strong> A closed vocabulary makes a
 * typo an error rather than a variant, and a half-imported roster is worse than a
 * rejected one: the ENM cannot tell which half landed. They fix the sheet and re-upload.
 *
 * <p><strong>Re-upload updates, it does not duplicate.</strong> The upsert key is
 * {@code (brand_id, lower(email))}, and V18's partial unique index is what enforces it —
 * the lookup below is a check-then-act two concurrent uploads can both win. A row with
 * no email cannot be upserted at all and is reported, because there is nothing to match
 * it on.
 *
 * <p><strong>Rows are never deleted.</strong> An expert dropped from the sheet is set
 * {@code INACTIVE} by hand; a sheet that silently deletes roster rows is how history
 * disappears.
 *
 * <p><strong>No payment-detail column, ever.</strong> A bank reference in a spreadsheet
 * that has been mailed around is precisely the exposure the encrypted column exists to
 * end (invariant 4). It is typed once, into the field, by a person.
 *
 * <p>The file itself is parsed in memory and never stored (invariant 14).
 */
@Service
public class ExpertImportService {

	/**
	 * Sheet column header → {@link ExpertForm} field, submitted with the file so the ENM
	 * does not have to rename their spreadsheet's headers. Not persisted: it describes
	 * one upload, and a saved mapping would go stale against a sheet that keeps being
	 * edited by hand.
	 */
	public record ImportMapping(Map<String, String> columns) {

		public ImportMapping {
			columns = columns == null ? Map.of() : Map.copyOf(columns);
		}
	}

	/**
	 * One thing wrong with one row.
	 *
	 * @param row    the sheet's own row number, header included — so "row 34" is row 34
	 *               in the ENM's spreadsheet and not the 34th data row
	 * @param column the sheet's column header, or the field when the problem is not
	 *               about one cell (a missing email has no column to blame)
	 * @param reason what to fix, in the ENM's terms. An unrecognised tag names the value
	 *               it did not recognise and the closest legal ones: "row 34 invalid"
	 *               against a closed vocabulary is a dead end for whoever has to fix the
	 *               sheet.
	 */
	public record RowProblem(int row, String column, String reason) {
	}

	/**
	 * What the ENM sees after a validate or an import.
	 *
	 * @param imported whether anything was written. Both endpoints answer this same
	 *                 record — {@code validate} always with {@code false} — because the
	 *                 response envelope carries a code and a message on failure and there
	 *                 is nowhere in it for fifty per-row reasons. A rejected import is a
	 *                 200 whose report says nothing was written, and the screen offers no
	 *                 "import anyway".
	 */
	public record ImportReport(
			String file,
			int rows,
			int created,
			int updated,
			List<RowProblem> problems,
			boolean imported) {
	}

	/** One parsed row: the sheet's row number, and its cells by header. */
	private record SheetRow(int number, Map<String, String> cells) {
	}

	/**
	 * A parsed file. The headers are kept separately from the rows so the mapping can be
	 * checked against a sheet that has headers and no data — otherwise "you mapped a
	 * column that is not there" and "your sheet is empty" would be the same message.
	 */
	private record Sheet(List<String> headers, List<SheetRow> rows) {
	}

	/** What a row parsed into, plus what was wrong with it. */
	private record Candidate(int row, String email, ExpertForm form, List<RowProblem> problems) {
	}

	/** Every field a mapping may target, taken from the form so the two cannot drift. */
	private static final Set<String> TARGET_FIELDS = Stream.of(ExpertForm.class.getRecordComponents())
			.map(RecordComponent::getName)
			.collect(Collectors.toUnmodifiableSet());

	/** Multi-value cells: "LAW, FINANCE" and "LAW; FINANCE" and "LAW|FINANCE" all work. */
	private static final String LIST_SEPARATORS = "[,;|]";

	private final ExpertRepository experts;
	private final ExpertService expertService;
	private final OwnershipGuard ownership;
	private final AuditService audit;
	private final Validator validator;

	ExpertImportService(ExpertRepository experts, ExpertService expertService, OwnershipGuard ownership,
			AuditService audit, Validator validator) {
		this.experts = experts;
		this.expertService = expertService;
		this.ownership = ownership;
		this.audit = audit;
		this.validator = validator;
	}

	/** The dry run: parse, validate, write nothing. */
	@Transactional(readOnly = true)
	public ImportReport validate(UUID brandId, MultipartFile file, ImportMapping mapping) {
		UUID brand = brandFor(brandId);
		List<Candidate> candidates = candidates(file, mapping);
		return report(file, candidates, upsertCounts(brand, candidates), false);
	}

	/**
	 * The real import. Writes every row or none.
	 *
	 * <p>{@code saveAndFlush} per row rather than one {@code saveAll} at the end, so a
	 * concurrent upload's duplicate email surfaces here as a
	 * {@link DataIntegrityViolationException} that rolls the whole import back — the
	 * index deciding, not the lookup.
	 */
	@Transactional
	public ImportReport importSheet(UUID brandId, MultipartFile file, ImportMapping mapping) {
		UUID brand = brandFor(brandId);
		List<Candidate> candidates = candidates(file, mapping);
		if (candidates.stream().anyMatch(candidate -> !candidate.problems().isEmpty()) || candidates.isEmpty()) {
			// Nothing is written and nothing is thrown: the report *is* the answer, and it
			// has to reach the screen that lists the bad rows.
			return report(file, candidates, new int[] { 0, 0 }, false);
		}

		int created = 0;
		int updated = 0;
		try {
			for (Candidate candidate : candidates) {
				Optional<Expert> existing = experts.findByBrandIdAndEmailIgnoreCase(brand, candidate.email());
				Expert expert = existing.orElseGet(() -> new Expert(brand, candidate.form().fullName()));
				ExpertSnapshot before = existing.map(ExpertSnapshot::of).orElse(null);

				expertService.apply(expert, candidate.form());
				Expert saved = experts.saveAndFlush(expert);

				audit.recordEvent(ExpertService.OBJECT_TYPE, saved.getId(),
						existing.isPresent() ? AuditAction.UPDATED : AuditAction.CREATED, actor(), before,
						ExpertSnapshot.of(saved, "Sheet import: " + fileName(file)));
				if (existing.isPresent()) {
					updated++;
				}
				else {
					created++;
				}
			}
		}
		catch (DataIntegrityViolationException ex) {
			// The index refused a row the lookup thought was new — another upload of the
			// same sheet committed while this one ran. Nothing is written; the ENM re-runs.
			throw new InvalidRequestException(
					"The roster changed while this sheet was importing. Nothing was written — upload it again.");
		}

		// One row against the brand, because the object acted on is the brand's roster and
		// no single expert is the subject. The per-expert rows above answer the other half.
		audit.recordEvent("BRAND", brand, AuditAction.IMPORTED, actor(), null, Map.of(
				"file", fileName(file), "rows", candidates.size(), "created", created, "updated", updated));

		return report(file, candidates, new int[] { created, updated }, true);
	}

	// --- brand -----------------------------------------------------------------

	/**
	 * Which brand's roster this sheet lands in — the caller's, or the one a GM named.
	 *
	 * <p>Same reasoning as {@code ExpertService.create}: a GM has no brand of their own,
	 * so somebody has to say, and {@link OwnershipGuard} is what decides whether they
	 * may. It is never a scope.
	 */
	private UUID brandFor(UUID requested) {
		UUID brand = requested != null ? requested : TenantContext.current().brandId();
		if (brand == null) {
			throw new InvalidRequestException("Name the brand this sheet belongs to");
		}
		ownership.assertCanAct(brand);
		return brand;
	}

	// --- parsing ---------------------------------------------------------------

	private List<Candidate> candidates(MultipartFile file, ImportMapping mapping) {
		Sheet sheet = parse(file);
		Map<String, String> columns = checkedMapping(mapping, sheet.headers());

		List<Candidate> candidates = new ArrayList<>();
		Map<String, Integer> emailRows = new HashMap<>();
		for (SheetRow row : sheet.rows()) {
			Candidate candidate = candidate(row, columns);
			// A sheet listing one address twice would have its two rows fight over one
			// upserted expert, and the second would silently win. That is the sheet being
			// wrong, so it is reported rather than resolved.
			if (candidate.email() != null) {
				Integer first = emailRows.putIfAbsent(candidate.email().toLowerCase(Locale.ROOT), row.number());
				if (first != null) {
					candidate.problems().add(new RowProblem(row.number(), columnFor(columns, "email"),
							"the same email is already on row " + first));
				}
			}
			candidates.add(candidate);
		}
		return candidates;
	}

	/**
	 * The mapping, checked against both ends: every target has to be a field of
	 * {@link ExpertForm} and every named column has to exist in the sheet.
	 *
	 * <p>A 400 rather than a row problem, because neither is wrong with a row — the whole
	 * upload is addressed at something that is not there, and reporting it fifty times
	 * over would bury it.
	 */
	private static Map<String, String> checkedMapping(ImportMapping mapping, List<String> headers) {
		if (mapping.columns().isEmpty()) {
			throw new InvalidRequestException("Map at least one sheet column onto an expert field");
		}
		Map<String, String> byTarget = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : mapping.columns().entrySet()) {
			String column = entry.getKey().trim();
			String target = entry.getValue() == null ? "" : entry.getValue().trim();
			if ("paymentDetail".equals(target)) {
				throw new InvalidRequestException(
						"Payment details are never imported from a sheet — set one on the expert's profile");
			}
			if (!TARGET_FIELDS.contains(target)) {
				throw new InvalidRequestException("'" + target + "' is not a field on an expert");
			}
			if (byTarget.put(target, column) != null) {
				throw new InvalidRequestException("Two columns are mapped onto '" + target + "'");
			}
		}
		if (!byTarget.containsKey("fullName")) {
			throw new InvalidRequestException("Map a column onto the expert's full name");
		}

		byTarget.values().stream()
				.filter(column -> !headers.contains(column))
				.findFirst()
				.ifPresent(column -> {
					throw new InvalidRequestException("The sheet has no column called '" + column + "'");
				});
		return byTarget;
	}

	/**
	 * One row's cells turned into a form, collecting every problem rather than stopping at
	 * the first: an ENM fixing a sheet wants the whole list, not one round trip per typo.
	 */
	private Candidate candidate(SheetRow row, Map<String, String> columns) {
		List<RowProblem> problems = new ArrayList<>();
		ExpertForm form = new ExpertForm(
				text(row, columns, "fullName"),
				text(row, columns, "email"),
				text(row, columns, "phone"),
				text(row, columns, "title"),
				text(row, columns, "institution"),
				tags(row, columns, "primaryFields", FieldTag.class, problems),
				tags(row, columns, "secondaryFields", FieldTag.class, problems),
				tags(row, columns, "letterTypes", LetterType.class, problems),
				single(row, columns, "tier", ExpertTier.class, problems),
				single(row, columns, "availability", Availability.class, problems),
				number(row, columns, "qualityScore", problems),
				number(row, columns, "standardFee", problems),
				text(row, columns, "recruitmentSource"),
				date(row, columns, "dateOnboarded", problems),
				text(row, columns, "notes"),
				// Unit 33. TARGET_FIELDS is derived from ExpertForm's components, so each of
				// these became mappable the moment it was added there; only the parsing is here.
				text(row, columns, "expertCode"),
				text(row, columns, "subSpecialization"),
				text(row, columns, "highestDegree"),
				text(row, columns, "degreeField"),
				text(row, columns, "degreeInstitution"),
				text(row, columns, "currentPosition"),
				single(row, columns, "affiliationType", AffiliationType.class, problems),
				text(row, columns, "country"),
				text(row, columns, "stateRegion"),
				integer(row, columns, "yearsExperience", problems),
				text(row, columns, "linkedinUrl"),
				tags(row, columns, "visaCategories", VisaCategory.class, problems),
				integer(row, columns, "publications", problems),
				integer(row, columns, "citations", problems),
				integer(row, columns, "hIndex", problems),
				integer(row, columns, "patents", problems),
				text(row, columns, "notableAwards"),
				text(row, columns, "professionalMemberships"),
				text(row, columns, "editorialRoles"),
				text(row, columns, "languages"),
				flag(row, columns, "rushAvailable", problems),
				integer(row, columns, "avgTurnaroundDays", problems));

		for (ConstraintViolation<ExpertForm> violation : validator.validate(form)) {
			String field = violation.getPropertyPath().toString();
			problems.add(new RowProblem(row.number(), columnFor(columns, field), violation.getMessage()));
		}
		if (form.email() == null || form.email().isBlank()) {
			// Not a Bean Validation constraint: an expert typed into the form may legitimately
			// have no email on file, but a sheet row without one cannot be matched to a row in
			// the roster, so re-uploading would create a second copy every time.
			problems.add(new RowProblem(row.number(), columnFor(columns, "email"),
					"an email is required to import — it is what a re-upload matches on"));
		}
		return new Candidate(row.number(), form.email(), form, problems);
	}

	private static String text(SheetRow row, Map<String, String> columns, String field) {
		String column = columns.get(field);
		if (column == null) {
			return null;
		}
		String value = row.cells().get(column);
		return value == null || value.isBlank() ? null : value.trim();
	}

	private <E extends Enum<E>> List<E> tags(SheetRow row, Map<String, String> columns, String field,
			Class<E> type, List<RowProblem> problems) {
		String value = text(row, columns, field);
		if (value == null) {
			return List.of();
		}
		List<E> parsed = new ArrayList<>();
		for (String part : value.split(LIST_SEPARATORS)) {
			if (part.isBlank()) {
				continue;
			}
			parse(type, part).ifPresentOrElse(parsed::add,
					() -> problems.add(unknownValue(row, columns, field, type, part)));
		}
		return parsed;
	}

	private <E extends Enum<E>> E single(SheetRow row, Map<String, String> columns, String field,
			Class<E> type, List<RowProblem> problems) {
		String value = text(row, columns, field);
		if (value == null) {
			return null;
		}
		return parse(type, value).orElseGet(() -> {
			problems.add(unknownValue(row, columns, field, type, value));
			return null;
		});
	}

	/**
	 * A whole number. Separate from {@link #number} rather than a cast of it: a citation
	 * count written "6,100" is a spreadsheet's formatting and must survive, but "4.9" in a
	 * publications column is the wrong column mapped and has to be reported, not truncated.
	 */
	private static Integer integer(SheetRow row, Map<String, String> columns, String field,
			List<RowProblem> problems) {
		String value = text(row, columns, field);
		if (value == null) {
			return null;
		}
		try {
			// Only the separators a spreadsheet adds are stripped — NOT every non-digit, which
			// would turn "4.9" into 49 and import the quality-score column as an h-index.
			return Integer.valueOf(value.replaceAll("[,\\s]", ""));
		}
		catch (NumberFormatException ex) {
			problems.add(new RowProblem(row.number(), columns.get(field), "'" + value + "' is not a whole number"));
			return null;
		}
	}

	/**
	 * Yes/No as a roster sheet writes it. An unmapped column is {@code false} rather than a
	 * problem — "we did not ask" and "no" are the same answer for a capability flag — but a
	 * cell that is neither is reported, because a typo silently meaning "no rush work" would
	 * quietly shrink the pool the ENM can offer an urgent case to.
	 */
	private static boolean flag(SheetRow row, Map<String, String> columns, String field,
			List<RowProblem> problems) {
		String value = text(row, columns, field);
		if (value == null) {
			return false;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "yes", "y", "true", "1" -> true;
			case "no", "n", "false", "0" -> false;
			default -> {
				problems.add(new RowProblem(row.number(), columns.get(field),
						"'" + value + "' is not yes or no"));
				yield false;
			}
		};
	}

	private static BigDecimal number(SheetRow row, Map<String, String> columns, String field,
			List<RowProblem> problems) {
		String value = text(row, columns, field);
		if (value == null) {
			return null;
		}
		try {
			// Thousands separators and a currency symbol are what a spreadsheet's own
			// formatting produces, so they are stripped rather than rejected.
			return new BigDecimal(value.replaceAll("[^0-9.\\-]", ""));
		}
		catch (NumberFormatException ex) {
			problems.add(new RowProblem(row.number(), columns.get(field), "'" + value + "' is not a number"));
			return null;
		}
	}

	private static LocalDate date(SheetRow row, Map<String, String> columns, String field,
			List<RowProblem> problems) {
		String value = text(row, columns, field);
		if (value == null) {
			return null;
		}
		try {
			return LocalDate.parse(value);
		}
		catch (DateTimeParseException ex) {
			problems.add(new RowProblem(row.number(), columns.get(field),
					"'" + value + "' is not a date — use YYYY-MM-DD"));
			return null;
		}
	}

	private static <E extends Enum<E>> RowProblem unknownValue(SheetRow row, Map<String, String> columns,
			String field, Class<E> type, String value) {
		List<String> closest = closest(type, value);
		return new RowProblem(row.number(), columns.get(field),
				"'%s' is not a recognised %s%s".formatted(value.trim(), label(type),
						closest.isEmpty() ? "" : " — did you mean " + String.join(", ", closest) + "?"));
	}

	private static String label(Class<?> type) {
		return FieldTag.class.equals(type) ? "field tag" : type.getSimpleName();
	}

	/** {@code "mechanical engg"} → {@code MECHANICAL_ENGINEERING}, exactly and only. */
	private static <E extends Enum<E>> Optional<E> parse(Class<E> type, String value) {
		String normalized = normalize(value);
		return Arrays.stream(type.getEnumConstants())
				.filter(constant -> constant.name().equals(normalized))
				.findFirst();
	}

	private static String normalize(String value) {
		return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_|_$", "");
	}

	/**
	 * The nearest legal values to something the vocabulary does not contain.
	 *
	 * <p>ponytail: shared-word heuristic, not edit distance. It gets the case that
	 * actually happens — a sheet writing "Mechanical Engg" or "Comp Sci" where the tag is
	 * one word longer — and it costs six lines. If ENMs report suggestions that miss,
	 * swap in Levenshtein over the normalized names; nothing else changes.
	 */
	private static <E extends Enum<E>> List<String> closest(Class<E> type, String value) {
		List<String> words = Arrays.stream(normalize(value).split("_")).filter(word -> !word.isEmpty()).toList();
		return Arrays.stream(type.getEnumConstants())
				.map(Enum::name)
				.map(name -> Map.entry(name, overlap(name, words)))
				.filter(scored -> scored.getValue() > 0)
				.sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
						.thenComparing(Map.Entry::getKey))
				.limit(3)
				.map(Map.Entry::getKey)
				.toList();
	}

	private static int overlap(String name, List<String> words) {
		return (int) words.stream()
				.filter(word -> word.length() >= 3
						&& Arrays.stream(name.split("_")).anyMatch(part -> part.startsWith(word)
								|| word.startsWith(part)))
				.count();
	}

	/** The header a field was mapped from, for a problem that has to name a column. */
	private static String columnFor(Map<String, String> columns, String field) {
		return columns.getOrDefault(field, field);
	}

	// --- the two formats --------------------------------------------------------

	/**
	 * CSV and XLSX, one row shape out of both.
	 *
	 * <p>Two parsers rather than one because the ENM uploads straight from Excel: XLSX
	 * costs Apache POI (~10 MB with transitives) against commons-csv's ~50 KB, which is
	 * the trade the tracker records. Everything after this method is format-blind, so the
	 * cost stops at the edge — there is one validator and one importer.
	 */
	private static Sheet parse(MultipartFile file) {
		String name = fileName(file).toLowerCase(Locale.ROOT);
		try {
			if (name.endsWith(".csv")) {
				return parseCsv(file);
			}
			if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
				return parseXlsx(file);
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		throw new InvalidRequestException("Upload a .csv or .xlsx sheet");
	}

	private static Sheet parseCsv(MultipartFile file) throws IOException {
		try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
				CSVParser parser = CSVFormat.DEFAULT.builder()
						.setHeader()
						.setSkipHeaderRecord(true)
						.setIgnoreEmptyLines(true)
						.setIgnoreSurroundingSpaces(true)
						.setTrim(true)
						.build()
						.parse(reader)) {

			List<String> headers = parser.getHeaderNames();
			List<SheetRow> rows = new ArrayList<>();
			for (CSVRecord record : parser) {
				Map<String, String> cells = new LinkedHashMap<>();
				// By position, not by record.get(name): a sheet with a duplicated or blank
				// header still parses, and a short row is padded rather than throwing.
				for (int column = 0; column < headers.size(); column++) {
					cells.put(headers.get(column), column < record.size() ? record.get(column) : "");
				}
				if (cells.values().stream().allMatch(value -> value == null || value.isBlank())) {
					continue;
				}
				// getRecordNumber() counts data records; +1 puts the header back so the number
				// matches what the ENM sees in their spreadsheet.
				rows.add(new SheetRow((int) record.getRecordNumber() + 1, cells));
			}
			return new Sheet(headers, rows);
		}
	}

	private static Sheet parseXlsx(MultipartFile file) throws IOException {
		try (var workbook = new XSSFWorkbook(file.getInputStream())) {
			var sheet = workbook.getSheetAt(0);
			Row header = sheet.getRow(sheet.getFirstRowNum());
			if (header == null) {
				return new Sheet(List.of(), List.of());
			}
			List<String> headers = new ArrayList<>();
			for (int column = 0; column < header.getLastCellNum(); column++) {
				headers.add(cellText(header.getCell(column)));
			}

			List<SheetRow> rows = new ArrayList<>();
			for (int number = header.getRowNum() + 1; number <= sheet.getLastRowNum(); number++) {
				Row row = sheet.getRow(number);
				if (row == null) {
					continue;
				}
				Map<String, String> cells = new LinkedHashMap<>();
				for (int column = 0; column < headers.size(); column++) {
					cells.put(headers.get(column), cellText(row.getCell(column)));
				}
				if (cells.values().stream().allMatch(String::isBlank)) {
					continue;
				}
				// POI counts rows from 0 and the ENM's spreadsheet counts from 1.
				rows.add(new SheetRow(number + 1, cells));
			}
			return new Sheet(headers, rows);
		}
	}

	/**
	 * A cell as the person who typed it would read it.
	 *
	 * <p>Numbers are the trap: POI hands back a double, so a fee of 1200 stringifies as
	 * "1200.0" and a date is a serial number. Both are converted here rather than in the
	 * validator, which only ever sees text.
	 */
	private static String cellText(Cell cell) {
		if (cell == null) {
			return "";
		}
		CellType type = cell.getCellType() == CellType.FORMULA
				? cell.getCachedFormulaResultType()
				: cell.getCellType();
		return switch (type) {
			case STRING -> cell.getStringCellValue().trim();
			case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
			case NUMERIC -> DateUtil.isCellDateFormatted(cell)
					? cell.getLocalDateTimeCellValue().toLocalDate().toString()
					: BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
			default -> "";
		};
	}

	// --- report ----------------------------------------------------------------

	/**
	 * How many of these rows are new and how many already exist, for the dry run's
	 * "3 new, 47 updated" line. One lookup per row, which is what an import does anyway;
	 * this runs on a file an ENM uploaded, not on a page load.
	 */
	private int[] upsertCounts(UUID brand, List<Candidate> candidates) {
		int created = 0;
		int updated = 0;
		for (Candidate candidate : candidates) {
			if (candidate.email() == null) {
				continue;
			}
			if (experts.findByBrandIdAndEmailIgnoreCase(brand, candidate.email()).isPresent()) {
				updated++;
			}
			else {
				created++;
			}
		}
		return new int[] { created, updated };
	}

	private static ImportReport report(MultipartFile file, List<Candidate> candidates, int[] counts,
			boolean imported) {
		List<RowProblem> problems = candidates.stream()
				.flatMap(candidate -> candidate.problems().stream())
				.sorted(Comparator.comparingInt(RowProblem::row).thenComparing(RowProblem::column,
						Comparator.nullsLast(Comparator.naturalOrder())))
				.toList();
		return new ImportReport(fileName(file), candidates.size(), counts[0], counts[1], problems, imported);
	}

	private static String fileName(MultipartFile file) {
		String name = file.getOriginalFilename();
		return name == null || name.isBlank() ? "sheet" : name;
	}

	private static UUID actor() {
		return TenantContext.current().memberId();
	}
}
