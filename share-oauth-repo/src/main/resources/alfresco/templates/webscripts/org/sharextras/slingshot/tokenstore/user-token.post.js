function main() {
    var userid = person.properties.userName,
        jsonData = jsonUtils.toObject(requestbody.content);
    preferenceService.setPreferences(userid, jsonData);
    logger.log("user-token.post : saved " + requestbody.content);
    model.jsonStr = requestbody.content;
}

main();