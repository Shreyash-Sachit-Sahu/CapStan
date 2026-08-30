package dev.capstan.backtest;

import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Harness scaffolding. Lives in dev.capstan.backtest because the payload it
 * accepts carries oracle rows -- see BatchLoader for why that matters.
 */
@RestController
@RequestMapping("/api/admin/batch")
@Validated
@RequiredArgsConstructor
public class BatchLoadController {

    private final BatchLoader loader;

    /**
     * @param batch label recorded on every loaded case, so {@code ?batch=} on the
     *              eval and backtest endpoints scores the batch it names rather
     *              than whatever happens to be in the database.
     */
    @PostMapping(path = "/load", consumes = MediaType.APPLICATION_JSON_VALUE)
    public BatchLoader.Result load(
            @RequestBody List<@Valid BatchRecord> body,
            @RequestParam(name = "batch", defaultValue = "v1") String batch) {
        return loader.load(body, batch);
    }
}
