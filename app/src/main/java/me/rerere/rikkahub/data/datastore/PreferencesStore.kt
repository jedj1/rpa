                    (preferences[SUB_AGENT_GLOBAL_CONCURRENCY_CAP]
                        ?: SubAgentDefaults.GLOBAL_CONCURRENCY_CAP).coerceIn(
                        SubAgentDefaults.MIN_GLOBAL_CONCURRENCY_CAP,
                        SubAgentDefaults.MAX_GLOBAL_CONCURRENCY_CAP,
                    ),
                // 必须读回: 上面 update 里写了 DYNAMIC_COLOR, 这里若缺这一行, Settings 的默认值
                // dynamicColor = true 会永远盖掉用户的选择 —— 表现为「动态取色随机失效/
                // 重启后回退」。
                // `!= false` 而不是 `?: true`: 没有该键(全新安装/升级)时同样按 true 处理, 但
                // 用户显式关闭(false)时不再被当成缺失值覆盖。
                dynamicColor = preferences[DYNAMIC_COLOR] != false,
                themeId = preferences[THEME_ID] ?: PresetThemes[0].id,
                customThemes = preferences[CUSTOM_THEMES]?.let { raw ->
                    runCatching { JsonInstant.decodeFromString<List<CustomTheme>>(raw) }.getOrElse {
