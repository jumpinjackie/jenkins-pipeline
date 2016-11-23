/**
 * MapGuide.groovy
 *
 * This is the jenkins build pipeline for MapGuide
 *
 * Env vars (set up from calling job):
 *
 * - MG_VER_MAJOR: MapGuide Major Version
 * - MG_VER_MINOR: MapGuide Minor Version
 * - MG_VER_REV: MapGuide Rev Version
 * - MG_OEM_BUILDPACK_DATE: The date of the OEM buildpack
 * - MG_BUILD_PLATFORM: x86 or x64
 * - MG_BUILD_CONFIG: Debug or Release
 * - MG_SVN_UPDATE: If a working copy already has been checked out, perform an update on it
 * - MG_CLEAR_BUILD_AREA: If true, will clear the build area before building
 * - MG_BUILD_ROOT: The root directory where build artifacts will be copied to
 * - MG_CLEANUP_AFTER: If builds and test pass, this will instruct the pipeline to clear the build and staging areas afterwards
 *
 * Environmental assumptions:
 * - It has been set up with the required software
 * - The following directories exist
 *    - C:\builds (build artifacts are copied here on successful builds)
 *    - C:\deps (build deps are stored here)
 *       - mapguide
 *       - fdo
 */
node ("master") {
    env.PATH = "${env.PATH};C:\\Program Files (x86)\\Subversion\\bin;C:\\Program Files\\doxygen\\bin;C:\\Program Files (x86)\\Graphviz2.38\\bin"
    env.ANT_HOME = "C:\\apache-ant-1.9.7"
    env.WITH_ARCSDE = "no"
    // The Visual Studio major version. This is used for tagging the mg-desktop artifact and determining the VS setup script
    def vs_version = "14"
    // This is called to set up the VS/MSBuild environment
    def vs_init_script = "C:\\Program Files (x86)\\Microsoft Visual C++ Build Tools\\vcbuildtools.bat"
    //def vs_init_script = "C:\\Program Files (x86)\\Microsoft Visual Studio ${vs_version}.0\\VC\\vcvarsall.bat"
    def release_label = "trunk" // Used for tagging installer artifacts
    //Where the pipeline will grab the mapguide build deps
    def mg_dep_root = "C:\\deps\\mapguide\\${release_label}"
    //Where the pipeline will grab the FDO binaries
    def fdo_version = "4.1.0"
    def fdo_dep_root = "C:\\deps\\fdo\\${fdo_version}"
    //Name of the directory where svn will checkout to
    def checkout_dir_name = "checkout"
    //SVN revision number to be set by polling for the latest rev from the poll url
    def svn_rev = "0"
    //The URL to checkout from
    def svn_checkout_url = "https://svn.osgeo.org/mapguide/sandbox/jng/diet_v2/MgDev_Slim"
    //The URL to poll the latest SVN revision number from. This differs from the poll URL as heavy use of SVN externals 
    //may mask the true revision number
    def svn_poll_url = "https://svn.osgeo.org/mapguide/sandbox/jng/diet_v2/MgDev"
    //Java SDK locations
    def java_home = [:]
    java_home["windows-x86-release"] = "C:\\Program Files (x86)\\Java\\jdk1.6.0_45"
    java_home["windows-x64-release"] = "C:\\Program Files\\Java\\jdk1.6.0_45"
    java_home["windows-x86-debug"] = "C:\\Program Files (x86)\\Java\\jdk1.6.0_45"
    java_home["windows-x64-debug"] = "C:\\Program Files\\Java\\jdk1.6.0_45"
    def build_pack = [:]
    build_pack["windows-x86-release"] = "mapguide-buildpack-Release-Win32-${release_label}-${env.MG_OEM_BUILDPACK_DATE}.exe"
    build_pack["windows-x64-release"] = "mapguide-buildpack-Release-x64-${release_label}-${env.MG_OEM_BUILDPACK_DATE}.exe"
    build_pack["windows-x86-debug"] = "mapguide-buildpack-Debug-Win32-${release_label}-${env.MG_OEM_BUILDPACK_DATE}.exe"
    build_pack["windows-x64-debug"] = "mapguide-buildpack-Debug-x64-${release_label}-${env.MG_OEM_BUILDPACK_DATE}.exe"
    def fdo_sdk = [:]
    fdo_sdk["windows-x86-release"] = "Fdo-${fdo_version}-x86.7z"
    fdo_sdk["windows-x64-release"] = "Fdo-${fdo_version}-x64.7z"
    fdo_sdk["windows-x86-debug"] = "Fdo-${fdo_version}-x86.7z"
    fdo_sdk["windows-x64-debug"] = "Fdo-${fdo_version}-x64.7z"
    echo "MG_SVN_UPDATE is: ${env.MG_SVN_UPDATE}"
    stage('SVN checkout') {
        //NOTE: Be sure to set the global SVN wc format to 1.6 as the current format (1.8)
        //has unacceptably slow checkout/update performance. Observed with the version of
        //SVNKit used by the current version of the SVN plugin (2.7.1)
        //
        //Some sample checkout times
        //
        //                | v1.8 wc | v1.6 wc
        // Full checkout  | 32m54s  | 8m54s
        // Update         | 11m24s  | 2m15s
        //
        // Relevant Issue: https://issues.tmatesoft.com/issue/SVNKIT-553
        //checkout([$class: 'SubversionSCM', additionalCredentials: [], excludedCommitMessages: '', excludedRegions: '', excludedRevprop: '', excludedUsers: '', filterChangelog: false, ignoreDirPropChanges: false, includedRegions: '', locations: [[credentialsId: '', depthOption: 'infinity', ignoreExternalsOption: false, local: './checkout', remote: 'https://svn.osgeo.org/mapguide/sandbox/jng/diet_v2/MgDev_Slim']], workspaceUpdater: [$class: 'UpdateUpdater']])
        if (fileExists("./${checkout_dir_name}")) {
            if (env.MG_SVN_UPDATE == "true") {
                echo "Performing SVN update"
                bat "svn update ./${checkout_dir_name}"
            } else {
                echo "Skipping SVN update (MG_SVN_UPDATE = ${env.MG_SVN_UPDATE})"
            }
        } else {
            echo "Performing full SVN checkout"
            bat "svn co ${svn_checkout_url} ./${checkout_dir_name}"
        }
        echo "Collecting SVN revision"
        dir("${checkout_dir_name}\\Installer\\scripts") {
            bat "svn info ${svn_poll_url} | perl revnum.pl > svnrev.txt"
            svn_rev = readFile("./svnrev.txt").trim()
        }
    }
    //Now set the appropriate version numbers and artifact names
    def mg_ver_major_minor_rev = "${env.MG_VER_MAJOR}.${env.MG_VER_MINOR}.${env.MG_VER_REV}"
    def inst_ver = "${mg_ver_major_minor_rev}.${svn_rev}"
    def inst_name = [:]
    inst_name["windows-x86-release"] = "MapGuideOpenSource-${inst_ver}-${release_label}-x86"
    inst_name["windows-x64-release"] = "MapGuideOpenSource-${inst_ver}-${release_label}-x64"
    def inst_setup_name = [:]
    inst_setup_name["windows-x86-release"] = "MapGuideOpenSource-${inst_ver}-${release_label}-InstantSetup-x86"
    inst_setup_name["windows-x64-release"] = "MapGuideOpenSource-${inst_ver}-${release_label}-InstantSetup-x64"
    inst_setup_name["windows-x86-debug"] = "MapGuideOpenSource-${inst_ver}-${release_label}-InstantSetup-x86-debug"
    inst_setup_name["windows-x64-debug"] = "MapGuideOpenSource-${inst_ver}-${release_label}-InstantSetup-x64-debug"
    def mgd_package_name = [:]
    mgd_package_name["windows-x86-release"] = "mg-desktop-${inst_ver}-net40-vc${vs_version}-x86"
    mgd_package_name["windows-x64-release"] = "mg-desktop-${inst_ver}-net40-vc${vs_version}-x64"
    stage('Download required build deps') {
        //Verify our build deps exist
        parallel (
            "windows-x86-release": {
                node("windows") {
                    //Verify our build deps exist
                    if (!fileExists("${mg_dep_root}\\${build_pack['windows-x86-release']}")) {
                        error "Build pack (${build_pack['windows-x86-release']}) does not exist at: ${mg_dep_root}"
                    }
                    if (!fileExists("${fdo_dep_root}\\${fdo_sdk['windows-x86-release']}")) {
                        error "Build pack (${fdo_sdk['windows-x86-release']}) does not exist at: ${fdo_dep_root}"
                    }
                }
            },
            "windows-x64-release": {
                node("windows") {
                    //Verify our build deps exist
                    if (!fileExists("${mg_dep_root}\\${build_pack['windows-x64-release']}")) {
                        error "Build pack (${build_pack['windows-x64-release']}) does not exist at: ${mg_dep_root}"
                    }
                    if (!fileExists("${fdo_dep_root}\\${fdo_sdk['windows-x64-release']}")) {
                        error "Build pack (${fdo_sdk['windows-x64-release']}) does not exist at: ${fdo_dep_root}"
                    }
                }
            }
        )        
        //TODO: In an AWS environment, we'd be stashing build deps on S3 and Download them
        //should the above fileExists() checks fail
    }
    stage('Setup build area') {
        if (env.MG_CLEAR_BUILD_AREA == "true") {
            dir("build_area") {
                echo "Current directory is: " + pwd()
                echo "Deleting this directory"
                deleteDir()
            }
            echo "SVN exporting to this directory"
            bat "svn export ./${checkout_dir_name} build_area --force -q"
        } else {
            echo "Skip clearing build area (MG_CLEAR_BUILD_AREA = ${env.MG_CLEAR_BUILD_AREA})"
        }
        stash includes: 'build_area/**/*', name: 'svn_build_area_export'
    }
    stage('Build MapGuide') {
        parallel (
            "windows-x86-release": {
                node("windows") {
                    dir ("./windows-x86-release") {
                        unstash 'svn_build_area_export'
                    }
                    echo "Building MapGuide v${inst_ver} (x86, release)"
                    dir ("./windows-x86-release/build_area") {
                        if (!fileExists("setenvironment.bat")) {
                            error "Sanity check fail: Did build_area un-stash properly?"
                        }
                    }
                }
            },
            "windows-x64-release": {
                node("windows") {
                    dir ("./windows-x64-release") {
                        unstash 'svn_build_area_export'
                    }
                    echo "Building MapGuide v${inst_ver} (x64, release)"
                    dir ("./windows-x64-release/build_area") {
                        if (!fileExists("setenvironment64.bat")) {
                            error "Sanity check fail: Did build_area un-stash properly?"
                        }
                    }
                }
            }
        )
    }
    stage('Copy build artifacts') {
    }
}