plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false
}

// В пути к проекту есть «:» (папка «programms:scripts:etc»), а для Java это разделитель
// classpath — R.jar и прочие артефакты не находятся. Поэтому сборку выносим в папку без «:».
if (':' in rootDir.absolutePath) {
    allprojects {
        layout.buildDirectory.set(File(System.getProperty("user.home"), ".formbot-build/${project.name}"))
    }
}
