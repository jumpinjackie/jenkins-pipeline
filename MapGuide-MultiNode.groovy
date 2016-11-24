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

class GlobalConfig {
    // The Visual Studio major version. This is used for tagging the mg-desktop artifact and determining the VS setup script
    static String vs_version = "14"
    // This is called to set up the VS/MSBuild environment
    static String vs_init_script = "C:\\Program Files (x86)\\Microsoft Visual C++ Build Tools\\vcbuildtools.bat"
    //def vs_init_script = "C:\\Program Files (x86)\\Microsoft Visual Studio ${vs_version}.0\\VC\\vcvarsall.bat"
    static String release_label = "trunk" // Used for tagging installer artifacts
    //Where the pipeline will grab the mapguide build deps
    static String mg_dep_root = "C:\\deps\\mapguide\\${release_label}"
    //Where the pipeline will grab the FDO binaries
    static String fdo_version = "4.1.0"
    static String fdo_dep_root = "C:\\deps\\fdo\\${fdo_version}"
    //Name of the directory where svn will checkout to
    static String checkout_dir_name = "checkout"
    //The URL to checkout from
    static String svn_checkout_url = "https://svn.osgeo.org/mapguide/sandbox/jng/diet_v2/MgDev_Slim"
    //The URL to poll the latest SVN revision number from. This differs from the poll URL as heavy use of SVN externals 
    //may mask the true revision number
    static String svn_poll_url = "https://svn.osgeo.org/mapguide/sandbox/jng/diet_v2/MgDev"
}

/**
 * Encapsulates all the key build enviroment settings and build logic
 */
class MgBuildEnvironment implements Serializable {
    final String name
    final String fdo_sdk
    final String build_pack
    final String java_home
    final String config
    final String platform
    final String ver_major
    final String ver_minor
    final String ver_rev
    final String vc_init_plat
    final String mg_init_script
    final String buildpack_date
    final String release_label
    final String mg_dep_root
    final String fdo_dep_root
    final String vs_version
    final String fdo_version
    String svn_rev
    MgBuildEnvironment(String name, String config, String platform, String ver_major, String ver_minor, String ver_rev, String buildpack_date, String vs_version, String fdo_version, String release_label, String mg_dep_root, String fdo_dep_root) {
        this.ver_major = ver_major
        this.ver_minor = ver_minor
        this.ver_rev = ver_rev
        this.buildpack_date = buildpack_date
        this.release_label = release_label
        this.mg_dep_root = mg_dep_root
        this.fdo_dep_root = fdo_dep_root
        this.vs_version = vs_version
        this.svn_rev = "0" //SVN revision number to be set by polling for the latest rev from the poll url
        this.name = name
        this.config = config
        this.platform = platform
        this.fdo_version = fdo_version
        this.fdo_sdk = "Fdo-${this.fdo_version}-${platform}.7z"

        this.mg_init_script = "setenvironment.bat"
        this.vc_init_plat = "x86"
        this.java_home = "C:\\Program Files (x86)\\Java\\jdk1.6.0_45"
        def win_plat = "Win32"
        if (platform == "x64") {
            win_plat = "x64"
            this.mg_init_script = "setenvironment64.bat"
            this.vc_init_plat = "x86_amd64"
            this.java_home = "C:\\Program Files\\Java\\jdk1.6.0_45"
        }

        this.build_pack = "mapguide-buildpack-${config}-${win_plat}-${this.release_label}-${this.buildpack_date}.exe"
    }
    String inst_ver() {
        return "${this.ver_major}.${this.ver_minor}.${this.ver_rev}.${this.svn_rev}"
    }
    String installer_name() {
        return "MapGuideOpenSource-${this.inst_ver()}-${this.release_label}-${this.config}"
    }
    String instantsetup_name() {
        return "MapGuideOpenSource-${this.inst_ver()}-${this.release_label}-InstantSetup-${this.config}"
    }
    String mgdesktop_package_name() {
        return "mg-desktop-${this.inst_ver()}-net40-vc${this.vs_version}-${this.config}"
    }
}

class MgBuildRun implements Serializable {
    final MgBuildEnvironment buildEnv
    MgBuildRun(MgBuildEnvironment buildEnv) {
        this.buildEnv = buildEnv
    }
    void build_dep_sanity_check(scriptEnv) {
        if (!scriptEnv.fileExists("${this.buildEnv.mg_dep_root}\\${this.buildEnv.build_pack}")) {
            scriptEnv.error "Build pack (${this.buildEnv.build_pack}) does not exist at: ${this.buildEnv.mg_dep_root}"
        } else {
            scriptEnv.echo "Sanity - MapGuide OEM buildpack is present"
        }
        if (!scriptEnv.fileExists("${this.buildEnv.fdo_dep_root}\\${this.buildEnv.fdo_sdk}")) {
            scriptEnv.error "Build pack (${this.buildEnv.fdo_sdk}) does not exist at: ${this.buildEnv.fdo_dep_root}"
        } else {
            scriptEnv.echo "Sanity - FDO binary SDK is present"
        }
    }
    void windows_build(stash, scriptEnv) {
        scriptEnv.dir ("./${this.buildEnv.name}") {
            scriptEnv.unstash stash
        }
        scriptEnv.echo "Building MapGuide v${this.buildEnv.inst_ver()} (${this.buildEnv.platform}, ${this.buildEnv.config})"
        scriptEnv.dir ("./${this.buildEnv.name}/build_area") {
            if (!scriptEnv.fileExists(this.buildEnv.mg_init_script)) {
                scriptEnv.error "Sanity check fail: Did build_area un-stash properly?"
            }
        }
    }
}

environments = [:]
environments["windows-x86-release"] = new MgBuildEnvironment("windows-x86-release",
                                                             "Release",
                                                             "x86",
                                                             env.MG_VER_MAJOR,
                                                             env.MG_VER_MINOR,
                                                             env.MG_VER_REV,
                                                             env.MG_OEM_BUILDPACK_DATE,
                                                             GlobalConfig.vs_version,
                                                             GlobalConfig.fdo_version,
                                                             GlobalConfig.release_label,
                                                             GlobalConfig.mg_dep_root,
                                                             GlobalConfig.fdo_dep_root)

environments["windows-x64-release"] = new MgBuildEnvironment("windows-x64-release",
                                                             "Release",
                                                             "x64",
                                                             env.MG_VER_MAJOR,
                                                             env.MG_VER_MINOR,
                                                             env.MG_VER_REV,
                                                             env.MG_OEM_BUILDPACK_DATE,
                                                             GlobalConfig.vs_version,
                                                             GlobalConfig.fdo_version,
                                                             GlobalConfig.release_label,
                                                             GlobalConfig.mg_dep_root,
                                                             GlobalConfig.fdo_dep_root)

environments["windows-x86-debug"] = new MgBuildEnvironment("windows-x86-debug",
                                                           "Debug",
                                                           "x86",
                                                           env.MG_VER_MAJOR,
                                                           env.MG_VER_MINOR,
                                                           env.MG_VER_REV,
                                                           env.MG_OEM_BUILDPACK_DATE,
                                                           GlobalConfig.vs_version,
                                                           GlobalConfig.fdo_version,
                                                           GlobalConfig.release_label,
                                                           GlobalConfig.mg_dep_root,
                                                           GlobalConfig.fdo_dep_root)

environments["windows-x64-debug"] = new MgBuildEnvironment("windows-x64-debug",
                                                           "Debug",
                                                           "x64",
                                                           env.MG_VER_MAJOR,
                                                           env.MG_VER_MINOR,
                                                           env.MG_VER_REV,
                                                           env.MG_OEM_BUILDPACK_DATE,
                                                           GlobalConfig.vs_version,
                                                           GlobalConfig.fdo_version,
                                                           GlobalConfig.release_label,
                                                           GlobalConfig.mg_dep_root,
                                                           GlobalConfig.fdo_dep_root)

node ("master") {
    env.PATH = "${env.PATH};C:\\Program Files (x86)\\Subversion\\bin;C:\\Program Files\\doxygen\\bin;C:\\Program Files (x86)\\Graphviz2.38\\bin"
    env.ANT_HOME = "C:\\apache-ant-1.9.7"
    env.WITH_ARCSDE = "no"
    
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
        if (fileExists("./${GlobalConfig.checkout_dir_name}")) {
            if (env.MG_SVN_UPDATE == "true") {
                echo "Performing SVN update"
                bat "svn update ./${GlobalConfig.checkout_dir_name}"
            } else {
                echo "Skipping SVN update (MG_SVN_UPDATE = ${env.MG_SVN_UPDATE})"
            }
        } else {
            echo "Performing full SVN checkout"
            bat "svn co ${GlobalConfig.svn_checkout_url} ./${GlobalConfig.checkout_dir_name}"
        }
        echo "Collecting SVN revision"
        dir("${GlobalConfig.checkout_dir_name}\\Installer\\scripts") {
            bat "svn info ${GlobalConfig.svn_poll_url} | perl revnum.pl > svnrev.txt"
            def rev = readFile("./svnrev.txt").trim()
            environments.each{ k, v -> v.svn_rev = rev }
        }
    }
    stage('Download required build deps') {
        //Verify our build deps exist
        parallel (
            "windows-x86-release": {
                node("windows") {
                    new MgBuildRun(environments['windows-x86-release']).build_dep_sanity_check(this)
                }
            },
            "windows-x64-release": {
                node("windows") {
                    new MgBuildRun(environments['windows-x64-release']).build_dep_sanity_check(this)
                }
            }
        )        
        //TODO: In an AWS environment, we'd be stashing build deps on S3 and Download them
        //should the above fileExists() checks fail
    }
    stage('Setup build area') {
        if (env.MG_CLEAR_BUILD_AREA == "true" || !fileExists("build_area")) {
            dir("build_area") {
                echo "Current directory is: " + pwd()
                echo "Deleting this directory"
                deleteDir()
            }
            echo "SVN exporting to this directory"
            bat "svn export ./${GlobalConfig.checkout_dir_name} build_area --force -q"
        } else {
            echo "Skip clearing build area (MG_CLEAR_BUILD_AREA = ${env.MG_CLEAR_BUILD_AREA})"
        }
        stash includes: 'build_area/**/*', name: 'svn_build_area_export'
    }
    stage('Build MapGuide') {
        parallel (
            "windows-x86-release": {
                node("windows") {
                    new MgBuildRun(environments["windows-x86-release"]).windows_build('svn_build_area_export', this)
                }
            },
            "windows-x64-release": {
                node("windows") {
                    new MgBuildRun(environments["windows-x64-release"]).windows_build('svn_build_area_export', this)
                }
            }
        )
    }
    stage('Copy build artifacts') {
    }
}