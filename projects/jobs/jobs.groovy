// Constants
def gerritBaseUrl = "ssh://jenkins@gerrit:29418"
def cartridgeBaseUrl = gerritBaseUrl + "/cartridges"
def platformToolsGitUrl = gerritBaseUrl + "/platform-management"

// Folders
def workspaceFolderName = "${WORKSPACE_NAME}"

def projectFolderName = workspaceFolderName + "/${PROJECT_NAME}"
def projectFolder = folder(projectFolderName)

/* def enableFolderName = projectFolderName + "/Features_to_Enable";
def enableFolder = folder(enableFolderName) { displayName('Features to Enable') }

def dpf_FolderName = projectFolderName + "/HCM_Project_with_Default_Features"
def dpf_Folder = folder(dpf_FolderName) { displayName('HCM Project with Default Features') }

def ha_FolderName = projectFolderName + "/HCM_Automation"
def ha_Folder = folder(ha_FolderName) { displayName('HCM Automation') }

def aftp_FolderName = projectFolderName + "/Apply_Feature_to_Project"
def aftp_Folder = folder(aftp_FolderName) { displayName('Apply Feature to Project') }
*/
// HCM Features Manager

def hfm_FolderName = projectFolderName + "/HCM_Features_Manager"
def hfm_Folder = folder(hfm_FolderName) { displayName('HCM Features Manager') }

def md_FolderName = hfm_FolderName + "/Manage_Department"
def md_Folder = folder(md_FolderName) { displayName('Manage Department') }

def cd_FolderName = hfm_FolderName + "/Create_Department"
def cd_Folder = folder(cd_FolderName) { displayName('Create Department') }

def pm_FolderName = hfm_FolderName + "/Person_Management"
def pm_Folder = folder(pm_FolderName) { displayName('Person Management') }

// HCM Features Enablement

def fe_FolderName = projectFolderName + "/HCM_Feature_Enablement"
def fe_Folder = folder(fe_FolderName) { displayName('HCM Feature Enablement') }

def wd_FolderName = fe_FolderName + "/Enable_Workforce_Development"
def wd_Folder = folder(wd_FolderName) { displayName('Enable Workforce Development') }

def mgs_FolderName = wd_FolderName + "/Manage_Goal_Setting"
def mgs_Folder = folder(mgs_FolderName) { displayName('Manage Goal Setting') }

// HCM Feature Templates

def ft_FolderName = projectFolderName + "/HCM_Feature_Templates"
def ft_Folder = folder(ft_FolderName) { displayName('HCM Feature Templates') }

// HCM Project Creation

def pc_FolderName = projectFolderName + "/HCM_Project_Creation"
def pc_Folder = folder(pc_FolderName) { displayName('HCM Project Creation') }

// ADOP Cartridge Management Folder

def cartridgeManagementFolderName= projectFolderName + "/Cartridge_Management"
def cartridgeManagementFolder = folder(cartridgeManagementFolderName) { displayName('Cartridge Management') }

// Cartridge List
def cartridge_list = []
readFileFromWorkspace("${WORKSPACE}/cartridges.txt").eachLine { line ->
    cartridge_repo_name = line.tokenize("/").last()
    local_cartridge_url = cartridgeBaseUrl + "/" + cartridge_repo_name
    cartridge_list << local_cartridge_url
}


// Jobs
def loadCartridgeJob = freeStyleJob(cartridgeManagementFolderName + "/Load_Cartridge")

// Setup Load_Cartridge
loadCartridgeJob.with{
    parameters{
        choiceParam('CARTRIDGE_CLONE_URL', cartridge_list, 'Cartridge URL to load')
    }
    environmentVariables {
        env('WORKSPACE_NAME',workspaceFolderName)
        env('PROJECT_NAME',projectFolderName)
    }
    wrappers {
        preBuildCleanup()
        injectPasswords()
        maskPasswords()
        sshAgent("adop-jenkins-master")
    }
    steps {
        shell('''#!/bin/bash -ex
        
# Clone Cartridge
git clone ${CARTRIDGE_CLONE_URL} cartridge

repo_namespace="${PROJECT_NAME}"
permissions_repo="${repo_namespace}/permissions"

# We trust everywhere
echo -e "#!/bin/sh\nexec ssh -o StrictHostKeyChecking=no \"\\\$@\"\n" > ${WORKSPACE}/custom_ssh
chmod +x ${WORKSPACE}/custom_ssh
export GIT_SSH="${WORKSPACE}/custom_ssh"

# Create repositories
mkdir ${WORKSPACE}/tmp
cd ${WORKSPACE}/tmp
while read repo_url; do
    if [ ! -z "${repo_url}" ]; then
        repo_name=$(echo "${repo_url}" | rev | cut -d'/' -f1 | rev | sed 's#.git$##g')
        target_repo_name="${repo_namespace}/${repo_name}"
        # Check if the repository already exists or not
        repo_exists=0
        list_of_repos=$(ssh -n -o StrictHostKeyChecking=no -p 29418 jenkins@gerrit gerrit ls-projects --type code)

        for repo in ${list_of_repos}
        do
            if [ ${repo} = ${target_repo_name} ]; then
                echo "Found: ${repo}"
                repo_exists=1
                break
            fi
        done

        # If not, create it
        if [ ${repo_exists} -eq 0 ]; then
            ssh -n -o StrictHostKeyChecking=no -p 29418 jenkins@gerrit gerrit create-project --parent "${permissions_repo}" "${target_repo_name}"
        else
            echo "Repository already exists, skipping create: ${target_repo_name}"
        fi
        
        # Populate repository
        git clone ssh://jenkins@gerrit:29418/"${target_repo_name}"
        cd "${repo_name}"
        git remote add source "${repo_url}"
        git fetch source
        git push origin +refs/remotes/source/*:refs/heads/*
        cd -
    fi
done < ${WORKSPACE}/cartridge/src/urls.txt

# Provision one-time infrastructure
if [ -d ${WORKSPACE}/cartridge/infra ]; then
    cd ${WORKSPACE}/cartridge/infra
    if [ -f provision.sh ]; then
        source provision.sh
    else
        echo "INFO: cartridge/infra/provision.sh not found"
    fi
fi

# Generate Jenkins Jobs
if [ -d ${WORKSPACE}/cartridge/jenkins/jobs ]; then
    cd ${WORKSPACE}/cartridge/jenkins/jobs
    if [ -f generate.sh ]; then
        source generate.sh
    else
        echo "INFO: cartridge/jenkins/jobs/generate.sh not found"
    fi
fi
''')
        systemGroovyCommand('''
import jenkins.model.*
import groovy.io.FileType

def jenkinsInstace = Jenkins.instance
def projectName = build.getEnvironment(listener).get('PROJECT_NAME')
def xmlDir = new File(build.getWorkspace().toString() + "/cartridge/jenkins/jobs/xml")
def fileList = []

xmlDir.eachFileRecurse (FileType.FILES) { file ->
    if(file.name.endsWith('.xml')) {
        fileList << file
    }
}
fileList.each {
	String configPath = it.path
  	File configFile = new File(configPath)
    String configXml = configFile.text
    ByteArrayInputStream xmlStream = new ByteArrayInputStream( configXml.getBytes() )
    String jobName = configFile.getName().substring(0, configFile.getName().lastIndexOf('.'))

    jenkinsInstace.getItem(projectName,jenkinsInstace).createProjectFromXML(jobName, xmlStream)
}
''')
        dsl {
            external("cartridge/jenkins/jobs/dsl/**/*.groovy")
        }

    }
    scm {
        git {
            remote {
                name("origin")
                url("${platformToolsGitUrl}")
                credentials("adop-jenkins-master")
            }
            branch("*/master")
        }
    }
}