package com.fr.colorostools;

interface IPackageServiceController {
    void clearApplicationProfileData(String packageName);
    boolean performDexOptMode(String packageName);
}