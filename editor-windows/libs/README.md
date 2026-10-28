# 固定依赖

`com.formdev:flatlaf:3.7.2`，Apache-2.0。此目录已随项目包含运行和构建需要的 JAR，离线构建无需下载。

官方地址：<https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2.jar>

SHA-256：`917aff3963c88d797d0fd9b9ccbd70f7681c101df9d11c59e2bc7a3a6c0fabf4`

缺失时在本模块运行：

```powershell
Invoke-WebRequest https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.7.2/flatlaf-3.7.2.jar -OutFile libs/flatlaf-3.7.2.jar
```

构建校验 SHA-256，并将类、资源、许可和原生库合并到应用 JAR，不需要用户单独配置 classpath。
