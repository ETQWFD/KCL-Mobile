plugins {
    java
}

tasks.withType<JavaCompile>().configureEach {
    // patches 类会打进 3.3.3 的合并 jar（JRE8 运行），必须以 --release 8 编译，
    // 否则 JDK21 会写入协变返回类型引用，JRE8 上加载即 NoSuchMethodError。
    options.release.set(8)
}
