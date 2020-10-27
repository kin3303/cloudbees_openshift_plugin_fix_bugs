public class OpenShiftClient extends KubernetesClient {

    def createOrUpdatePlatformSpecificResources(String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {
        if (OFFLINE) return null
        createOrUpdateRoute(clusterEndpoint, namespace, serviceDetails, accessToken)
    }

    def getRoutes(String clusterEndpoint, String namespace, String accessToken) {
        println clusterEndpoint
        def response = doHttpGet(clusterEndpoint, "/apis/route.openshift.io/v1/namespaces/${namespace}/routes", accessToken, true)
        logger DEBUG, "Routes: ${response}"
        return response?.data?.items
    }

    def createOrUpdateRoute(String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {
        String routeName = getServiceParameter(serviceDetails, 'routeName')

        if (!routeName) {
            //bail out - not creating route if weren't asked to
            return null
        }

        def response = doHttpGet(clusterEndpoint,
                "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
                accessToken, /*failOnErrorCode*/ false)
        if (response.status == 200){
            logger INFO, "Route $routeName found in $namespace, updating route ..."
            createOrUpdateRoute(/*existingRoute*/ response.data, routeName, clusterEndpoint, namespace, serviceDetails, accessToken)
        } else if (response.status == 404){
            logger INFO, "Route $routeName does not exist in $namespace, creating route ..."
            createOrUpdateRoute(/*existingRoute*/ null, routeName, clusterEndpoint, namespace, serviceDetails, accessToken)
        } else {
            handleError("Route check failed. ${response.statusLine}")
        }
    }


    def createOrUpdateRoute(def existingRoute, String routeName, String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {
        String routeHostname = getServiceParameter(serviceDetails, 'routeHostname')
        String routePath = getServiceParameter(serviceDetails, 'routePath', '/')
        String routeTargetPort = getServiceParameter(serviceDetails, 'routeTargetPort')

        def payload = buildRoutePayload(routeName, routeHostname, routePath, routeTargetPort, serviceDetails, existingRoute)

        def createRoute = existingRoute == null
        doHttpRequest(createRoute ? POST : PUT,
                clusterEndpoint,
                createRoute?
                        "/apis/route.openshift.io/v1/namespaces/${namespace}/routes" :
                        "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
                ['Authorization' : accessToken],
                /*failOnErrorCode*/ true,
                payload)
    }

    String buildRoutePayload(String routeName, String routeHostname, String routePath, String routeTargetPort, def serviceDetails, def existingRoute) {
        def serviceName = getServiceNameToUseForDeployment(serviceDetails)
        def json = new JsonBuilder()
        def result = json{
            kind "Route"
            apiVersion "route.openshift.io/v1"
            metadata {
                name routeName
            }
            spec {
                if (routeHostname) {
                    host routeHostname
                }
                path routePath
                to {
                    kind "Service"
                    name serviceName
                }
                if (routeTargetPort) {
                    port {
                        targetPort routeTargetPort
                    }
                }
            }
        }
        // build the final payload by merging with the existing
        // route definition
        def payload = existingRoute
        if (payload) {
            payload = mergeObjs(payload, result)
        } else {
            payload = result
        }

        return (new JsonBuilder(payload)).toPrettyString()
    }

    def undeployService(
            EFClient efClient,
            String accessToken,
            String clusterEndpoint,
            String namespace,
            String serviceName,
            String serviceProjectName,
            String applicationName,
            String applicationRevisionId,
            String clusterName,
            String envProjectName,
            String environmentName,
            String serviceEntityRevisionId = null) {

        super.undeployService(
                efClient,
                accessToken,
                clusterEndpoint,
                namespace,
                serviceName,
                serviceProjectName,
                applicationName,
                applicationRevisionId,
                clusterName,
                envProjectName,
                environmentName,
                serviceEntityRevisionId)

        def serviceDetails = efClient.getServiceDeploymentDetails(
                serviceName,
                serviceProjectName,
                applicationName,
                applicationRevisionId,
                clusterName,
                envProjectName,
                environmentName,
                serviceEntityRevisionId)

        removeRoute(clusterEndpoint, namespace, serviceDetails, accessToken)
    }

    def removeRoute(String clusterEndpoint, String namespace, def serviceDetails, String accessToken) {

        String routeName = getServiceParameter(serviceDetails, 'routeName')
        if (!routeName) {
            //bail out - nothing to do if the route is not specified
            return null
        }

        def response = doHttpGet(clusterEndpoint,
                "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
                accessToken, /*failOnErrorCode*/ false)

        if (response.status == 200){
            logger DEBUG, "Route $routeName found in $namespace"

            def existingRoute = response.data
            def serviceName = getServiceNameToUseForDeployment(serviceDetails)
            if (existingRoute?.spec?.to?.kind == 'Service' && existingRoute?.spec?.to?.name == serviceName) {
                logger DEBUG, "Deleting route $routeName in $namespace"

                doHttpRequest(DELETE,
                        clusterEndpoint,
                        "/apis/route.openshift.io/v1/namespaces/${namespace}/routes/${routeName}",
                        ['Authorization' : accessToken],
                        /*failOnErrorCode*/ true)
            }

        } else if (response.status == 404){
            logger INFO, "Route $routeName does not exist in $namespace, no route to remove"
        } else {
            handleError("Route check failed. ${response.statusLine}")
        }
    }

    def convertVolumes(data){
        def jsonData = parseJsonToList(data)
        def result = []
        for (item in jsonData){
            def name = formatName(item.name)
            if(item.hostPath){
                result << [name: name, hostPath: [path : item.hostPath]]
            } else {
                result << [name: name, emptyDir: {}]
            }
        }
        return (new JsonBuilder(result))
    }

    def getDeploymentConfigs(String clusterEndPoint, String namespace, String accessToken, parameters = [:]) {
        def path = "/apis/apps.openshift.io/v1/namespaces/${namespace}/deploymentconfigs"
        def response = doHttpGet(clusterEndPoint,
            path,
            accessToken, /*failOnErrorCode*/ false, null)
        def tempDeployments = []
        response?.data?.items?.each{ deployment ->
            def fit = false
            deployment?.spec?.selector.each{ k, v ->
                parameters.labelSelector.split(',').each{ selector ->
                    if ((k + '=' + v) == selector){
                        fit = true
                    }
                }
            }
            if (fit){
                tempDeployments.push(deployment)
            }
        }

        response.data.items = tempDeployments
        def str = response.data ? (new JsonBuilder(response.data)).toPrettyString(): response.data
        logger DEBUG, "Deployments found: $str"

        response.status == 200 ? response.data : null
    }

    String buildDeploymentPayload(def args, def existingDeployment, def imagePullSecretsList){

        if (!args.defaultCapacity) {
            args.defaultCapacity = 1
        }

        def deploymentStrategy = getDeploymentStrategy(args)
        println new JsonBuilder(args).toPrettyString()

        def json = new JsonBuilder()
        //Get the message calculation out of the way
        def replicaCount
        def maxSurgeValue
        def maxUnavailableValue
        boolean isCanary = isCanaryDeployment(args)

        if (isCanary) {
            replicaCount = getServiceParameter(args, 'numberOfCanaryReplicas', 1).toInteger()
            maxSurgeValue = 1
            maxUnavailableValue = 1
        } else {
            if (deploymentStrategy && deploymentStrategy == 'rollingDeployment') {
                def minAvailabilityCount = getServiceParameter(args, 'minAvailabilityCount')
                def minAvailabilityPercentage = getServiceParameter(args, 'minAvailabilityPercentage')
                def maxRunningCount = getServiceParameter(args, 'maxRunningCount')
                def maxRunningPercentage = getServiceParameter(args, 'maxRunningPercentage')

                if (!(minAvailabilityPercentage as boolean ^ minAvailabilityCount as boolean)) {
                    throw new PluginException("Either minAvailabilityCount or minAvailabilityPercentage must be set")
                }
                if (!(maxRunningPercentage as boolean ^ maxRunningCount as boolean)) {
                    throw new PluginException("Either maxRunningCount or maxRunningPercentage must be set")
                }

                replicaCount = args.defaultCapacity.toInteger()
                maxSurgeValue = maxRunningCount ? maxRunningCount.toInteger() : "${maxRunningPercentage.toInteger() + 100}%"

                if (minAvailabilityCount) {
                    maxUnavailableValue = args.defaultCapacity.toInteger() - minAvailabilityCount.toInteger()
                }
                else {
                    maxUnavailableValue = "${100 - minAvailabilityPercentage.toInteger()}%"
                }
            }
            else {

                replicaCount = args.defaultCapacity.toInteger()
                maxSurgeValue = args.maxCapacity ? (args.maxCapacity.toInteger() - args.defaultCapacity.toInteger()) : 1
                maxUnavailableValue =  args.minCapacity ?
                    (args.defaultCapacity.toInteger() - args.minCapacity.toInteger()) : 1
            }

        }

        def volumeData = convertVolumes(args.volumes)
        def serviceName = getServiceNameToUseForDeployment(args)
        def deploymentName = getDeploymentName(args)
        def selectorLabel = getSelectorLabelForDeployment(args, serviceName, isCanary)

        String apiPath = versionSpecificAPIPath('deployments')
        int deploymentTimeoutInSec = getServiceParameter(args, 'deploymentTimeoutInSec', 120).toInteger()

        def deploymentFlag = isCanary ? 'canary' : 'stable'

        def result = json {
            kind "Deployment"
            apiVersion apiPath
            metadata {
                name deploymentName
            }
            spec {
                replicas replicaCount
                progressDeadlineSeconds deploymentTimeoutInSec
                strategy {
                    rollingUpdate {
                        maxUnavailable maxUnavailableValue
                        maxSurge maxSurgeValue
                    }
                }
                selector {
                    matchLabels {
                        "ec-svc" selectorLabel
                        "ec-track" deploymentFlag
                    }
                }
                template {
                    metadata {
                        name deploymentName
                        labels {
                            "ec-svc" selectorLabel
                            "ec-track" deploymentFlag
                        }
                    }
                    spec{
                        containers(args.container.collect { svcContainer ->
                            def limits = [:]
                            if (svcContainer.memoryLimit) {
                                limits.memory = "${svcContainer.memoryLimit}M"
                            }
                            if (svcContainer.cpuLimit) {
                                Integer cpu = convertCpuToMilliCpu(svcContainer.cpuLimit.toFloat())
                                limits.cpu = "${cpu}m"
                            }

                            def requests = [:]
                            if (svcContainer.memorySize) {
                                requests.memory = "${svcContainer.memorySize}M"
                            }
                            if (svcContainer.cpuCount) {
                                Integer cpu = convertCpuToMilliCpu(svcContainer.cpuCount.toFloat())
                                requests.cpu = "${cpu}m"
                            }

                            def containerResources = [:]
                            if (limits) {
                                containerResources.limits = limits
                            }
                            if (requests) {
                                containerResources.requests = requests
                            }

                            def livenessProbe = [:]
                            def readinessProbe = [:]

                            // Only HTTP based Liveness probe is supported
                            if(getServiceParameter(svcContainer, 'livenessHttpProbePath') && getServiceParameter(svcContainer, 'livenessHttpProbePort')){
                                def httpHeaderName = getServiceParameter(svcContainer, 'livenessHttpProbeHttpHeaderName')
                                def httpHeaderValue = getServiceParameter(svcContainer, 'livenessHttpProbeHttpHeaderValue')
                                def livenessCommand = getServiceParameter(svcContainer, 'livenessCommand') 
                                def livenessFailureThreshold = getServiceParameter(svcContainer, 'livenessFailureThreshold')
                                def livenessSuccessThreshold = getServiceParameter(svcContainer, 'livenessSuccessThreshold') 
                                def livenessTimeoutSeconds = getServiceParameter(svcContainer, 'livenessTimeoutSeconds')
                                def livenessInitialDelay = getServiceParameter(svcContainer, 'livenessInitialDelay')
                                def livenessPeriod = getServiceParameter(svcContainer, 'livenessPeriod')
 
                                if(!livenessCommand && httpHeaderName && httpHeaderValue){
                                    def httpHeader = [name:"", value: ""]
                                    livenessProbe = [httpGet:[path:"", port:"", httpHeaders:[httpHeader]], ]
                                    livenessProbe.httpGet.path = getServiceParameter(svcContainer, 'livenessHttpProbePath')
                                    livenessProbe.httpGet.port = (getServiceParameter(svcContainer, 'livenessHttpProbePort')).toInteger()
                                    httpHeader.name = httpHeaderName
                                    httpHeader.value = httpHeaderValue
                                } else if(!livenessCommand) {
                                    livenessProbe = [httpGet:[path:"", port:""]]
                                    livenessProbe.httpGet.path = getServiceParameter(svcContainer, 'livenessHttpProbePath')
                                    livenessProbe.httpGet.port = (getServiceParameter(svcContainer, 'livenessHttpProbePort')).toInteger()
                                } else {
                                    livenessProbe = [exec: [command:[:]]]
                                    livenessProbe.exec.command = ["${livenessCommand}"] 
                                }

                                livenessProbe.initialDelaySeconds = livenessInitialDelay.toInteger()
                                livenessProbe.periodSeconds = livenessPeriod.toInteger()
                                livenessProbe.failureThreshold = livenessFailureThreshold.toInteger()
                                livenessProbe.successThreshold = livenessSuccessThreshold.toInteger()
                                livenessProbe.timeoutSeconds = livenessTimeoutSeconds.toInteger()

                            } else {
                                livenessProbe = null
                            }

                            def readinessCommand = getServiceParameter(svcContainer, 'readinessCommand')
                            if(readinessCommand){
                                readinessProbe = [exec: [command:[:]]]
                                readinessProbe.exec.command = ["${readinessCommand}"]

                                def readinessInitialDelay = getServiceParameter(svcContainer, 'readinessInitialDelay')
                                readinessProbe.initialDelaySeconds = readinessInitialDelay.toInteger()
                                def readinessPeriod = getServiceParameter(svcContainer, 'readinessPeriod')
                                readinessProbe.periodSeconds = readinessPeriod.toInteger()
                            } else {
                                readinessProbe = null
                            }

                            [
                                    name: formatName(svcContainer.containerName),
                                    image: "${svcContainer.imageName}:${svcContainer.imageVersion?:'latest'}",
                                    command: svcContainer.entryPoint?.split(','),
                                    args: svcContainer.command?.split(','),
                                    livenessProbe: livenessProbe,
                                    readinessProbe: readinessProbe,
                                    ports: svcContainer.port?.collect { port ->
                                        [
                                                name: formatName(port.portName),
                                                containerPort: port.containerPort.toInteger(),
                                                protocol: "TCP"
                                        ]
                                    },
                                    volumeMounts: (parseJsonToList(svcContainer.volumeMounts)).collect { mount ->
                                                        [
                                                            name: formatName(mount.name),
                                                            mountPath: mount.mountPath
                                                        ]

                                        },
                                    env: svcContainer.environmentVariable?.collect { envVar ->
                                        if(envVar.value.contains('secretKeyRef') || envVar.value.contains('configMapRef')) {
                                            [
                                                name: envVar.environmentVariableName,
                                                valueFrom: new JsonSlurper().parseText(envVar.value)
                                            ]
                                        } else {
                                            [
                                                name: envVar.environmentVariableName,
                                                value: envVar.value
                                            ]
                                        }
                                    },
                                    resources: containerResources
                            ]
                        })
                        imagePullSecrets( imagePullSecretsList?.collect { pullSecret ->
                            [name: pullSecret]
                        })
                        volumes(volumeData.content)
                    }
                }

            }
        }

        def payload = existingDeployment
        if (payload) {
            payload = mergeObjs(payload, result)
        } else {
            payload = result
        }
        return ((new JsonBuilder(payload)).toPrettyString())
    }
}
