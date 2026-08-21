package dev.frostguard.app;

import dev.frostguard.api.runtime.RuntimeChannel;
import dev.frostguard.api.runtime.WorkspacePaths;

public final class ApplicationTitle {
    private ApplicationTitle() {
    }

    public static String current() {
        return format(WorkspacePaths.current().channel(), RuntimeVersion.current(),
                RuntimeInstanceIdentity.current());
    }

    static String format(RuntimeChannel channel, String version, String instanceLabel) {
        String title = String.format("%s · %s", channel.productName(), instanceLabel);
        return version == null || version.isBlank() ? title : title + " · v" + version;
    }
}
