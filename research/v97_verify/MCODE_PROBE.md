# MCODE_PROBE — StringsLocaleParityTest 验证

日期：2026-10-02

## 命令

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --console=plain :app:testDebugUnitTest --tests "com.xiufeigo.opencodeshell.StringsLocaleParityTest"
```

## Gradle 最后几行

```
> Task :app:processDebugUnitTestJavaRes UP-TO-DATE
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 3s
52 actionable tasks: 1 executed, 51 up-to-date
```

## 结果（app/build/test-results/testDebugUnitTest/TEST-com.xiufeigo.opencodeshell.StringsLocaleParityTest.xml）

| tests | failures | errors | skipped |
| ----- | -------- | ------ | ------- |
| 3     | 0        | 0      | 0       |
