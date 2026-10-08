package dev.frostguard.tasks.dailies;

import dev.frostguard.api.configs.TemplatesEnum;
import dev.frostguard.api.domain.ImageSearchResultData;
import dev.frostguard.engine.helper.TemplateSearchHelper;
import dev.frostguard.engine.helper.TemplateSearchHelper.SearchConfig;

/** Finds the first Intel marker variant without recapturing an unchanged map. */
final class IntelMissionFrameScanner {

    private IntelMissionFrameScanner() {
    }

    static Match findFirst(
            TemplateSearchHelper.Frame frame,
            TemplatesEnum[] orderedTemplates,
            SearchConfig config) {
        ImageSearchResultData last = new ImageSearchResultData(false, null, 0.0);
        for (TemplatesEnum template : orderedTemplates) {
            last = frame.locatePatternMono(template, config);
            if (last.isFound()) {
                return new Match(template, last);
            }
        }
        return new Match(null, last);
    }

    record Match(TemplatesEnum template, ImageSearchResultData result) {
        boolean found() {
            return template != null && result != null && result.isFound();
        }
    }
}
