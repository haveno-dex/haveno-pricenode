# How to upgrade the Gradle version

Visit the [Gradle website](https://gradle.org/releases) and decide the:

 - desired version
 - desired distribution type
 - what is the sha256 for the version and type chosen above

Adjust the following command with tha arguments above and execute it twice:

```asciidoc
./gradlew wrapper --gradle-version 8.2.1 \
    --distribution-type bin \
    --gradle-distribution-sha256-sum 03ec176d388f2aa99defcadc3ac6adf8dd2bce5145a129659537c0874dea5ad1
```

The first execution should automatically update:

- `haveno-pricenode/gradle/wrapper/gradle-wrapper.properties`

The second execution should then update:

- `haveno-pricenode/gradle/wrapper/gradle-wrapper.jar`
- `haveno-pricenode/gradlew`
- `haveno-pricenode/gradlew.bat`

The four updated files are ready to be committed.
