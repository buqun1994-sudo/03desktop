#!/usr/bin/env node

import { randomBytes } from "node:crypto";
import { chmodSync, closeSync, existsSync, mkdirSync, openSync, rmdirSync, unlinkSync, writeFileSync } from "node:fs";
import { spawnSync } from "node:child_process";
import path from "node:path";
import { fileURLToPath } from "node:url";

const outputDirectory = process.argv[2]?.trim();
if (!outputDirectory || !path.isAbsolute(outputDirectory)) {
  console.error("Usage: node generate-release-signing.mjs /absolute/path/output-directory");
  process.exit(2);
}
const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(scriptDirectory, "../..");
const relativeToRepository = path.relative(repositoryRoot, outputDirectory);
if (relativeToRepository === "" || (!relativeToRepository.startsWith("..") && !path.isAbsolute(relativeToRepository))) {
  console.error("Signing material output must stay outside the repository.");
  process.exit(2);
}

const keytool = process.env.JAVA_HOME
  ? path.join(process.env.JAVA_HOME, "bin", "keytool")
  : "keytool";
const storeFileName = "fossify-file-manager-car-release.jks";
const alias = "fossify-file-manager-car-release";
const storeFile = path.join(outputDirectory, storeFileName);
const propertiesFile = path.join(outputDirectory, "signing.properties");
const storePassword = randomBytes(32).toString("hex");
const keyPassword = randomBytes(32).toString("hex");

let directoryCreated = false;
let storeCreated = false;
let propertiesCreated = false;
try {
  mkdirSync(outputDirectory, { recursive: false, mode: 0o700 });
  directoryCreated = true;
  const generated = spawnSync(keytool, [
    "-genkeypair",
    "-noprompt",
    "-keystore", storeFile,
    "-storetype", "JKS",
    "-storepass", storePassword,
    "-alias", alias,
    "-keypass", keyPassword,
    "-keyalg", "RSA",
    "-keysize", "4096",
    "-sigalg", "SHA256withRSA",
    "-validity", "10000",
    "-dname", "CN=Fossify File Manager Car Adaptation Distribution, OU=Third-Party Open Source Adaptation, O=9.9 Studio, C=CN",
  ], { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  if (generated.status !== 0) {
    throw new Error(generated.stderr.trim() || "keytool failed");
  }
  storeCreated = true;
  chmodSync(storeFile, 0o600);

  const descriptor = openSync(propertiesFile, "wx", 0o600);
  propertiesCreated = true;
  try {
    writeFileSync(descriptor, [
      `storeFile=${storeFileName}`,
      `storePassword=${storePassword}`,
      `keyAlias=${alias}`,
      `keyPassword=${keyPassword}`,
      "",
    ].join("\n"), "utf8");
  } finally {
    closeSync(descriptor);
  }
  console.log("Created Fossify car adaptation release signing material.");
} catch (error) {
  if (propertiesCreated || existsSync(propertiesFile)) unlinkSync(propertiesFile);
  if (storeCreated || existsSync(storeFile)) unlinkSync(storeFile);
  if (directoryCreated && existsSync(outputDirectory)) rmdirSync(outputDirectory);
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
}
