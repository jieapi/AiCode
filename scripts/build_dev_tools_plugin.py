import json
import zipfile

tools_plugin = {
    "manifestVersion": 1,
    "id": "community.dev-tools",
    "name": {"default": "Dev Tools Suite", "zh-CN": "开发工程工具箱"},
    "version": "1.0.0",
    "publisher": "community",
    "repository": "https://github.com/community/dev-tools",
    "description": {"default": "Project tools for Flutter, Android, Web & Python", "zh-CN": "为当前项目提供构建、运行、调试与清理工具"},
    "license": "MIT",
    "engines": {
        "pluginApi": "^1.0.0"
    },
    "permissions": [
        {
            "id": "workspace.tools",
            "required": True,
            "reason": {"default": "Register project workspace tools", "zh-CN": "注册并向项目添加工作区工具"},
            "scope": {
                "ids": ["run-app", "build-apk", "clean-cache", "android-debug", "open-terminal", "code-format", "run-test"]
            }
        }
    ],
    "contributes": {
        "workspaceTools": [
            {
                "id": "run-app",
                "title": {"default": "Run App", "zh-CN": "运行应用"},
                "description": {"default": "Launch Flutter app with debug support", "zh-CN": "启动 Flutter 应用，支持调试模式"},
                "icon": "play",
                "category": "Flutter",
                "pageFile": "pages/run-app.json"
            },
            {
                "id": "build-apk",
                "title": {"default": "Build APK", "zh-CN": "构建 APK"},
                "description": {"default": "Build release APK package", "zh-CN": "构建发布安装包"},
                "icon": "hammer",
                "category": "Flutter",
                "pageFile": "pages/build-apk.json"
            },
            {
                "id": "clean-cache",
                "title": {"default": "Clean Cache", "zh-CN": "清理缓存"},
                "description": {"default": "Clean build cache files", "zh-CN": "清理构建缓存文件"},
                "icon": "trash",
                "category": "Flutter",
                "pageFile": "pages/clean-cache.json"
            },
            {
                "id": "android-debug",
                "title": {"default": "Android Debug", "zh-CN": "Android 调试"},
                "description": {"default": "Connect to device for debugging", "zh-CN": "连接设备进行调试"},
                "icon": "android",
                "category": "Android",
                "pageFile": "pages/android-debug.json"
            },
            {
                "id": "open-terminal",
                "title": {"default": "Open Terminal", "zh-CN": "打开终端"},
                "description": {"default": "Open terminal in project directory", "zh-CN": "在项目目录打开终端"},
                "icon": "terminal",
                "category": "通用",
                "pageFile": "pages/open-terminal.json"
            },
            {
                "id": "code-format",
                "title": {"default": "Format Code", "zh-CN": "代码格式化"},
                "description": {"default": "Format source code with linter", "zh-CN": "执行格式化检查"},
                "icon": "code",
                "category": "通用",
                "pageFile": "pages/code-format.json"
            },
            {
                "id": "run-test",
                "title": {"default": "Run Tests", "zh-CN": "运行测试"},
                "description": {"default": "Execute project test suite", "zh-CN": "执行项目测试用例"},
                "icon": "flask",
                "category": "通用",
                "pageFile": "pages/run-test.json"
            }
        ]
    }
}

# 按照通用 AdaptiveCard 标准构建的草图 ②「运行应用」全屏页面
run_app_page = {
    "type": "AdaptiveCard",
    "version": "1.5",
    "body": [
        {
            "type": "tabset",
            "tabs": [
                {
                    "title": "配置",
                    "items": [
                        {
                            "type": "card",
                            "title": "运行参数",
                            "items": [
                                {
                                    "type": "select",
                                    "id": "mode",
                                    "label": "运行模式",
                                    "options": [
                                        {"value": "debug", "label": "调试模式 (debug)"},
                                        {"value": "release", "label": "发布模式 (release)"},
                                        {"value": "profile", "label": "性能分析 (profile)"}
                                    ],
                                    "default": "debug"
                                },
                                {
                                    "type": "switch",
                                    "id": "hotReload",
                                    "label": "热重载",
                                    "hint": "保存后自动刷新",
                                    "default": True
                                },
                                {
                                    "type": "select",
                                    "id": "device",
                                    "label": "设备选择",
                                    "options": [
                                        {"value": "auto", "label": "已连接的设备 (自动)"},
                                        {"value": "chrome", "label": "Chrome (Web)"},
                                        {"value": "emulator", "label": "Android 模拟器"}
                                    ],
                                    "default": "auto"
                                }
                            ]
                        },
                        {
                            "type": "card",
                            "title": "高级选项",
                            "collapsible": True,
                            "expandedDefault": True,
                            "items": [
                                {
                                    "type": "input.list",
                                    "id": "params",
                                    "label": "--dart-define",
                                    "placeholder": "--dart-define=KEY=VALUE",
                                    "defaultValues": ["--dart-define=API_URL=https://api.example.com"]
                                }
                            ]
                        }
                    ]
                },
                {
                    "title": "脚本",
                    "items": [
                        {
                            "type": "code",
                            "code": "flutter run -d $device --$mode $params",
                            "language": "bash"
                        }
                    ]
                },
                {
                    "title": "说明",
                    "items": [
                        {
                            "type": "markdown",
                            "content": "### Flutter 运行工具说明\n\n- **调试模式**：启用 Dart VM 断点与热重载支持。\n- **设备选择**：默认自动发现已连接的 ADB 手机或浏览器。\n- **高级选项**：可通过 `--dart-define` 传入编译期常量。"
                        }
                    ]
                }
            ]
        },
        {
            "type": "submit",
            "title": "运行应用",
            "commandTemplate": "echo '[Flutter] Starting with mode=$mode, device=$device...' && flutter run -d $device --$mode $params",
            "terminalTitle": "Flutter 运行"
        }
    ]
}

def make_simple_page(title_zh, cmd):
    return {
        "type": "AdaptiveCard",
        "version": "1.5",
        "body": [
            {
                "type": "tabset",
                "tabs": [
                    {
                        "title": "脚本",
                        "items": [
                            {"type": "code", "code": cmd, "language": "bash"}
                        ]
                    }
                ]
            },
            {
                "type": "submit",
                "title": title_zh,
                "commandTemplate": cmd,
                "terminalTitle": title_zh
            }
        ]
    }

zip_path = "dev-tools-1.0.0.zip"
with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED) as zf:
    zf.writestr("plugin.json", json.dumps(tools_plugin, ensure_ascii=False, indent=2))
    zf.writestr("pages/run-app.json", json.dumps(run_app_page, ensure_ascii=False, indent=2))
    zf.writestr("pages/build-apk.json", json.dumps(make_simple_page("构建 APK", "flutter build apk --release"), ensure_ascii=False, indent=2))
    zf.writestr("pages/clean-cache.json", json.dumps(make_simple_page("清理缓存", "flutter clean"), ensure_ascii=False, indent=2))
    zf.writestr("pages/android-debug.json", json.dumps(make_simple_page("Android 调试", "adb devices -l"), ensure_ascii=False, indent=2))
    zf.writestr("pages/open-terminal.json", json.dumps(make_simple_page("打开终端", "pwd && ls -la"), ensure_ascii=False, indent=2))
    zf.writestr("pages/code-format.json", json.dumps(make_simple_page("代码格式化", "dart format ."), ensure_ascii=False, indent=2))
    zf.writestr("pages/run-test.json", json.dumps(make_simple_page("运行测试", "flutter test"), ensure_ascii=False, indent=2))

print(f"Generated {zip_path}")
