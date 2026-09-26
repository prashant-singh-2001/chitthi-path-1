package com.chitthi.search;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * One query does both halves of FR9: {@code translated_tsv @@ websearch_to_tsquery}
 * for the English translation (the generated tsvector's own GIN index), and
 * {@code original_text ILIKE '%...%'} for the original script - Postgres has
 * no stemming for Indic scripts, so a substring match through the existing
 * {@code pg_trgm} trigram index is what "search" means there. A hit is
 * matched by whichever side actually found it; both can never independently
 * apply to the same row's snippet, since a page's own language only ever
 * lands on one side.
 *
 * <p>{@code ts_headline}'s {@code StartSel}/{@code StopSel} are set to empty
 * strings: by default it wraps matches in {@code <b>...</b>}, but the
 * surrounding text is the user's own uploaded content, verbatim and
 * unescaped - rendering that as HTML client-side would be a stored-XSS
 * vector. The snippet is plain text end to end instead.
 *
 * <p>Built with {@link NamedParameterJdbcTemplate} rather than a fixed
 * {@code @Query} string: the tag and year filters are optional, and a
 * Postgres native query can't infer a null parameter's type cleanly when the
 * filter it belongs to might not even be in the WHERE clause.
 */
@Repository
public class SearchRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SearchRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<SearchHit> search(String owner, String q, String tag, Integer year, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("owner", owner)
                .addValue("q", q)
                .addValue("likeQ", "%" + escapeForLike(q) + "%")
                .addValue("limit", limit);

        StringBuilder sql = new StringBuilder("""
                SELECT p.document_id AS document_id, d.title AS title, d.year AS year,
                       d.tags::text AS tags_json, p.page_no AS page_no,
                       CASE WHEN p.translated_tsv @@ websearch_to_tsquery('english', :q) THEN 'TRANSLATED' ELSE 'ORIGINAL' END AS matched_in,
                       CASE WHEN p.translated_tsv @@ websearch_to_tsquery('english', :q)
                            THEN ts_headline('english', coalesce(p.translated_text, ''), websearch_to_tsquery('english', :q),
                                              'MaxFragments=1,MaxWords=25,MinWords=8,StartSel=,StopSel=')
                            ELSE substring(coalesce(p.original_text, '') FROM greatest(1, strpos(coalesce(p.original_text, ''), :q) - 60) FOR 200)
                       END AS snippet,
                       (ts_rank(p.translated_tsv, websearch_to_tsquery('english', :q))
                            + coalesce(similarity(p.original_text, :q), 0)) AS rank
                FROM page p
                JOIN document d ON d.id = p.document_id
                WHERE d.owner_id = :owner
                  AND (p.translated_tsv @@ websearch_to_tsquery('english', :q) OR p.original_text ILIKE :likeQ)
                """);
        if (tag != null) {
            sql.append(" AND d.tags @> to_jsonb(ARRAY[:tag]::text[])\n");
            params.addValue("tag", tag);
        }
        if (year != null) {
            sql.append(" AND d.year = :year\n");
            params.addValue("year", year);
        }
        sql.append(" ORDER BY rank DESC, p.page_no ASC LIMIT :limit");

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new SearchHit(
                UUID.fromString(rs.getString("document_id")),
                rs.getString("title"),
                (Integer) rs.getObject("year"),
                parseTags(rs.getString("tags_json")),
                rs.getInt("page_no"),
                rs.getString("snippet"),
                rs.getString("matched_in"),
                rs.getDouble("rank")));
    }

    /** {@code %}, {@code _} and {@code \} are ILIKE wildcards/escape characters - a literal search term must not trigger them. */
    private String escapeForLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private List<String> parseTags(String tagsJson) {
        try {
            return objectMapper.readValue(tagsJson, objectMapper.getTypeFactory()
                    .constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }
}
