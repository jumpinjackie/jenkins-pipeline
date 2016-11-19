/**
 * MapGuide.groovy
 *
 * This is the jenkins build pipeline for MapGuide
 *
 * Env vars (set up calling job):
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
 */
node {
    env.PATH = "${env.PATH};C:\\Program Files (x86)\\Subversion\\bin"
    env.ANT_HOME = "C:\\apache-ant-1.9.7"
    env.WITH_ARCSDE = "no"
    if (env.MG_BUILD_PLATFORM != "x86" && env.MG_BUILD_PLATFORM != "x64") {
        error "Unknown platform: ${env.MG_BUILD_PLATFORM}"
    }
    if (env.MG_BUILD_CONFIG != "Debug" && env.MG_BUILD_CONFIG != "Release") {
        error "Unknown config: ${env.MG_BUILD_CONFIG}"
    }
    def vs_version = "14"
    def vs_init_script = "C:\\Program Files (x86)\\Microsoft Visual C++ Build Tools\\vcbuildtools.bat"
    def release_label = "trunk"
    def build_area = "./build_area_${env.MG_BUILD_PLATFORM}_${MG_BUILD_CONFIG}"
    def mg_dep_root = "C:\\deps\\mapguide\\${release_label}"
    def fdo_version = "4.1.0"
    def mg_ver_major_minor_rev = "${env.MG_VER_MAJOR}.${env.MG_VER_MINOR}.${env.MG_VER_REV}"
    def fdo_dep_root = "C:\\deps\\fdo\\${fdo_version}"
    def checkout_dir_name = "checkout"
    def svn_rev = "0" //To be set by polling for the latest rev from the poll url
    def svn_poll_url = "https://svn.osgeo.org/mapguide/sandbox/jng/diet_v2/MgDev"
    def svn_checkout_url = "https://svn.osgeo.org/mapguide/sandbox/jng/diet_v2/MgDev_Slim"
    def staging_dir_name = "staging_${env.MG_BUILD_PLATFORM}"
    def build_pack = "mapguide-buildpack-${env.MG_BUILD_CONFIG}-Win32-${release_label}-${env.MG_OEM_BUILDPACK_DATE}.exe"
    def fdo_sdk = "Fdo-${fdo_version}-${env.MG_BUILD_PLATFORM}.7z"
    def checkout_dir_mgdev = "checkout"
    def vcbuild_plat = "x86"
    def setenv_script_name = "setenvironment"
    if (env.MG_BUILD_PLATFORM == "x86") {
        env.JAVA_HOME = "C:\\Program Files (x86)\\Java\\jdk1.6.0_45"
    } else {
        vcbuild_plat = "x86_amd64"
        setenv_script_name = "setenvironment64"
        env.JAVA_HOME = "C:\\Program Files\\Java\\jdk1.6.0_45"
        build_pack = "mapguide-buildpack-${env.MG_BUILD_CONFIG}-x64-${release_label}-${env.MG_OEM_BUILDPACK_DATE}.exe"
    }
    if (!fileExists("${mg_dep_root}\\${build_pack}")) {
        error "Build pack (${build_pack}) does not exist at: ${mg_dep_root}"
    }
    if (!fileExists("${fdo_dep_root}\\${fdo_sdk}")) {
        error "Build pack (${fdo_sdk}) does not exist at: ${fdo_dep_root}"
    }
    def staging_path_abs = ""
    def build_area_abs = ""
    def build_installer_abs = ""
    def build_instantsetup_abs = ""
    //Capture absolute paths
    dir("${build_area}") {
        build_area_abs = pwd()
    }
    dir("${build_area}/Installer") {
        build_installer_abs = pwd()
    }
    dir("${build_area}/InstantSetup") {
        build_instantsetup_abs = pwd()
    }
    dir("./${staging_dir_name}") {
        staging_path_abs = pwd()
    }
    echo "MG_SVN_UPDATE is: ${env.MG_SVN_UPDATE}"
    stage('SVN checkout') { // for display purposes
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
    echo "Building MapGuide v${env.MG_VER_MAJOR}.${env.MG_VER_MINOR}.${env.MG_VER_REV}.${svn_rev} (${env.MG_BUILD_PLATFORM}, ${env.MG_BUILD_CONFIG})"
    def inst_ver = "${mg_ver_major_minor_rev}.${svn_rev}"
    def inst_name = "MapGuideOpenSource-${inst_ver}-${release_label}-${env.MG_BUILD_PLATFORM}"
    def inst_setup_name = "MapGuideOpenSource-${inst_ver}-${release_label}-InstantSetup-${env.MG_BUILD_PLATFORM}"
    def mgd_package_name = "mg-desktop-${inst_ver}-net40-vc${vs_version}-${env.MG_BUILD_PLATFORM}"
    stage('Download required build deps') {
        //TODO: In an AWS environment, we'd be stashing build deps on S3 and download them here
    }
    stage('Setup build area') {
        dir(staging_path_abs) {
            echo "Current directory is: " + pwd()
            echo "Deleting this directory"
            deleteDir()
        }
        if (env.MG_CLEAR_BUILD_AREA == "true") {
            dir(build_area) {
                echo "Current directory is: " + pwd()
                echo "Deleting this directory"
                deleteDir()
            }
            echo "SVN exporting to this directory"
            bat "svn export ./${checkout_dir_name} ${build_area} --force -q"
        } else {
            echo "Skip clearing build area (MG_CLEAR_BUILD_AREA = ${env.MG_CLEAR_BUILD_AREA})"
        }
    }
    stage('Copy build deps to build area') {
        dir("${build_area}/Oem") {
            bat "${mg_dep_root}\\${build_pack} -y -o."
        }
        //Clean out FDO dirs if they exist (as we're extracting a new copy)
        dir ("${build_area}/Oem/FDO/Bin") {
            deleteDir()
        }
        dir ("${build_area}/Oem/FDO/Inc") {
            deleteDir()
        }
        dir ("${build_area}/Oem/FDO/Lib") {
            deleteDir()
        }
        dir ("${build_area}/Oem/FDO/Lib64") {
            deleteDir()
        }
        //Extract FDO binaries
        bat "7z x ${fdo_dep_root}\\${fdo_sdk} -y -o\"${build_area}/Oem/FDO\""
        dir ("${build_area}/Oem/FDO") {
            if (env.MG_BUILD_PLATFORM == "x86") {
                bat "rename Bin Release"
                bat "mkdir Bin"
                bat "move Release Bin"
            } else {
                bat "rename Bin Release64"
                bat "mkdir Bin"
                bat "move Release64 Bin"
                bat "rename Lib Lib64"
            }
        }
        dir("${build_area}") {
            bat "cscript updateversion.vbs /major:${env.MG_VER_MAJOR} /minor:${env.MG_VER_MINOR} /point:${env.MG_VER_REV} /build:${svn_rev}"
            bat "call stampassemblies.bat ${inst_ver}"
            echo "Stamped version: ${inst_ver}"
        }
    }
    stage('Build MapGuide') {
        dir("${build_area}") {
            //Build server/web/mg-desktop and docs in parallel
            parallel server_web_desktop: {
                dir("${build_area}") {
                    bat """call "${vs_init_script}" ${vcbuild_plat}
cd ${build_area_abs}
call ${setenv_script_name}.bat
call build.bat -w=server
if %ERRORLEVEL% NEQ 0 exit /b 1
call build.bat -w=web
if %ERRORLEVEL% NEQ 0 exit /b 1
call build.bat -w=server -a=install -o="${staging_path_abs}"
if %ERRORLEVEL% NEQ 0 exit /b 1
call build.bat -w=web -a=install -o="${staging_path_abs}"
if %ERRORLEVEL% NEQ 0 exit /b 1
call build_desktop.bat x86
if %ERRORLEVEL% NEQ 0 exit /b 1"""
                }
            }, docs: {
                dir("${build_area}") {
                    bat """call "${vs_init_script}" ${vcbuild_plat}
cd ${build_area_abs}
call ${setenv_script_name}.bat
call build.bat -w=doc
if %ERRORLEVEL% NEQ 0 exit /b 1
call build.bat -w=doc -a=install -o="${staging_path_abs}"
if %ERRORLEVEL% NEQ 0 exit /b 1"""
                }
            }
            failFast: true
        }
    }
    stage('Build Installer / InstantSetup') {
        //Prepare the staging area so that InstantSetup and Installer steps have the same set of files to work with
        dir("${build_area}") {
            bat """call "${vs_init_script}" ${vcbuild_plat}
cd ${build_area_abs}
call ${setenv_script_name}.bat
cd ${build_installer_abs}
call build.bat -a=prepare -source="${staging_path_abs}"
if %ERRORLEVEL% NEQ 0 exit /b 1"""
        }
        //Build windows installer and InstantSetup bundles in parallel
        parallel installer: {
            dir("${build_area}") {
                bat """call "${vs_init_script}" ${vcbuild_plat}
cd ${build_area_abs}
call ${setenv_script_name}.bat
cd ${build_installer_abs}
call build.bat -a=generate -source="${staging_path_abs}"
if %ERRORLEVEL% NEQ 0 exit /b 1
call build.bat -a=build -source="${staging_path_abs}" -version=${inst_ver} -name=${inst_name}
if %ERRORLEVEL% NEQ 0 exit /b 1
move /Y ${build_installer_abs}\\Output\\en-US\\${inst_name}.exe "${staging_path_abs}" """
            }
        }, instantsetup: {
            dir("${build_area}") {
                bat """call "${vs_init_script}" ${vcbuild_plat}
cd ${build_area_abs}
call ${setenv_script_name}.bat
cd ${build_instantsetup_abs}
msbuild /p:Configuration=${env.MG_BUILD_CONFIG};Platform="Any CPU" MgInstantSetup.sln
if %ERRORLEVEL% NEQ 0 exit /b 1
cd out/${env.MG_BUILD_CONFIG}
mkdir "${staging_path_abs}/Setup"
copy /Y *.exe "${staging_path_abs}/Setup"
copy /Y *.pdb "${staging_path_abs}/Setup"
copy /Y *.dll "${staging_path_abs}/Setup"
copy /Y *.config "${staging_path_abs}/Setup"
cd "${staging_path_abs}"
7z a ${inst_setup_name}.exe -mmt -mx5 -sfx7z.sfx CS-Map Server Web Setup"""
            }
        }, mg_desktop: {
            dir("${staging_path_abs}") {
                bat "7z a -mx9 ${mgd_package_name}.zip Desktop DesktopSamples"
            }
        }
        failFast: true
    }
    stage('Copy build artifacts') {
        dir("${env.MG_BUILD_ROOT}\\mapguide\\${mg_ver_major_minor_rev}") {
            def out_dir = pwd()
            bat """copy /Y ${staging_path_abs}\\${inst_name}.exe ${out_dir}
copy /Y ${staging_path_abs}\\${inst_setup_name}.exe ${out_dir}
copy /Y ${staging_path_abs}\\${mgd_package_name}.zip ${out_dir}"""
        }
        echo "MG_CLEANUP_AFTER is ${env.MG_CLEANUP_AFTER}"
        if (env.MG_CLEANUP_AFTER == "true") {
            dir ("${staging_path_abs}") {
                echo "Cleaning up: ${pwd()}"
                deleteDir()
            }
            dir ("${build_area}") {
                echo "Cleaning up: ${pwd()}"
                deleteDir()
            }
        }
    }
}