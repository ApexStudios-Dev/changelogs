const express = require('express')
const app = express()
const port = 3000

const fs = require('fs')
const path = require('path')
const semver = require('semver')

// directory name changelogs are stored in
// relative to __dirname
// path should be similar to `__dirname/<${changelogsDir}>/<version>/<modid>.txt`
const changelogsDir = 'changelogs'

// all found modids
let modIds = [ ]

// all found game versions
let gameVersions = [ ]

// all loaded changelog file data
// so we do not load files more than once
let changelogs = [ ]

// all endpoints that have been setup
// this is so endpoints dont get doubly setup
let setupEndpoints = [ ]

// build path to changelog file
function buildChangelogPath(modId, version) {
    return path.join(__dirname, changelogsDir, version, `${modId}.txt`)
}

// build endpoint name
function buildEndPoint(modId, version) {
    if(version && version !== 'latest') return `/${modId}/${version}`
    else return `/${modId}`
}

// get changelog file data, loading if not already loaded
function getOrLoadChangelog(modId, version, latest) {
    const changelogKey = `${modId}-${version}`
    if(changelogs[changelogKey]) return changelogs[changelogKey]
    else {
        const filePath = buildChangelogPath(modId, version)

        if(fs.existsSync(filePath) && fs.lstatSync(filePath).isFile()) {
            const data = fs.readFileSync(filePath).toString('utf8')
            const lines = data.split('\n')
            // remove first line, when generated on jenkins, it contains the git commit sha which we don't want
            // and since these changelog files are ultimately going to be auto generated during builds on jenkins
            // and pushed someway to some accessibly repo, we do not want that included commit sha
            lines.shift()
            const changelog = lines.join('\n')
            changelogs[changelogKey] = changelog
            return changelog
        } else {
            if(latest) return `Missing changelog file for mod/version: ${modId}-latest`
            else return `Missing changelog file for mod/version: ${changelogKey}`
        }
    }
}

// set up endpoint
function setupChangelogEndpoint(changelog, endpoint) {
    if(setupEndpoints.includes(endpoint)) return
    setupEndpoints.push(endpoint)
    console.log(`Setting up endpoint: '${endpoint}'`)

    app.get(endpoint, (req, res) => {
        res.type('txt')
        res.send(changelog)
    })
}

// setup endpoint for mod at specific version
function setupModChangelog(modId, version) {
    const filePath = buildChangelogPath(modId, version)
    if(path.extname(filePath) !== '.txt') return
    if(!fs.lstatSync(filePath).isFile()) return
    if(!modIds.includes(modId)) modIds.push(modId)
    const changelog = getOrLoadChangelog(modId, version, false)
    setupChangelogEndpoint(changelog, buildEndPoint(modId, version))
}

// searches for all changelog files and sets up endpoints for them
// collecting list of possible game versions at same time
function setupModChangelogs(dirPath, file) {
    const fullPath = path.join(dirPath, file)

    if(fs.lstatSync(fullPath).isDirectory()) {
        const version = path.basename(fullPath)

        if(!gameVersions.includes(version) && semver.parse(version)) {
            console.log(`Found Minecraft Version: ${version}`)
            gameVersions.push(version)
            fs.readdirSync(fullPath).forEach(file => setupModChangelog(path.basename(file, '.txt'), version))
        }
    }
}

// main entry point
function setupChangelogs(changelogDir) {
    const fullPath = path.join(__dirname, changelogDir)
    const files = fs.readdirSync(fullPath)
    files.forEach(file => setupModChangelogs(fullPath, file))
    setupLatestChangelogs()
}

// search all found game versions and return the latest version
// using semver for version comparisons
function findLatestVersion() {
    let latestVersion = undefined

    gameVersions.forEach(version => {
        if(latestVersion) {
            if(semver.compare(version, latestVersion) > 0) {
                latestVersion = version
            }
        } else {
            latestVersion = version
        }
    })

    return latestVersion
}

// setup endpoints for all found mods using the latest version for the changelog
function setupLatestChangelogs() {
    const latestVersion = findLatestVersion()
    console.log(`Latest version: ${latestVersion}`)
    modIds.forEach(modId => setupChangelogEndpoint(getOrLoadChangelog(modId, latestVersion, true), buildEndPoint(modId, 'latest')))
}

setupChangelogs('changelogs')

// setup some default & very basic 404 page
app.use((req, res, next) => res.status(404).send('Unknown endpoint! (404)'))
// run the app
app.listen(port, () => console.log(`Running on port ${port}`))
