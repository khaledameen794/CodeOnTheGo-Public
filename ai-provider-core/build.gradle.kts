plugins {
	`java-library`
	id("org.jetbrains.kotlin.jvm")
}

dependencies {
	implementation(libs.common.jkotlin)
	implementation(libs.google.gson)
	testImplementation(libs.tests.junit)
}
