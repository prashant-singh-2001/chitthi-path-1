package com.chitthi.document.repository;

import com.chitthi.document.model.Page;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface PageRepository extends JpaRepository<Page, UUID> {

    List<Page> findByDocumentIdOrderByPageNo(UUID documentId);

    List<Page> findByDocumentIdAndPageNoBetweenOrderByPageNo(UUID documentId, int firstPage, int lastPage);

    /**
     * The translate stage's compare-and-set: only a page still OCR_DONE with
     * the exact text it was loaded with is updated. The text-hash guard stops
     * a translation started before a user's edit (Day 10) from landing after
     * that edit reset the page back to OCR_DONE with different text.
     */
    @Modifying
    @Query("UPDATE Page p SET p.translatedText = :translatedText, "
            + "p.status = com.chitthi.document.model.PageStatus.TRANSLATED "
            + "WHERE p.id = :id AND p.status = com.chitthi.document.model.PageStatus.OCR_DONE "
            + "AND p.textHash = :textHash")
    int markTranslated(@Param("id") UUID id, @Param("translatedText") String translatedText,
                        @Param("textHash") String textHash);

    /**
     * The TTS stage's compare-and-set: only a page still TRANSLATED is
     * updated, so a redelivered message that lands after a user's edit reset
     * the page back to OCR_DONE cannot resurrect it as AUDIO_DONE.
     */
    @Modifying
    @Query("UPDATE Page p SET p.status = com.chitthi.document.model.PageStatus.AUDIO_DONE "
            + "WHERE p.id = :id AND p.status = com.chitthi.document.model.PageStatus.TRANSLATED")
    int markAudioDone(@Param("id") UUID id);
}
