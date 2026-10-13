            is GitHubReleaseChecker.CheckResult.Failed -> buildJsonObject {
                put("error", "check_failed")
                put("detail", result.message)
            }
            // Update checking is deliberately off in this fork (see GitHubReleaseChecker.enabled):
            // the APK comes from CI and is installed by hand, so there is nothing to
            // report. Say so plainly rather than fabricating an "up to date".
            is GitHubReleaseChecker.CheckResult.Disabled -> buildJsonObject {
                put("error", "update_check_disabled")
                put(
                    "detail",
                    "Update checking is disabled in this build. Install new builds from the CI artifact.",
                )
            }
