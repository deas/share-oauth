function main()
{
    var userid = person.properties.userName,
        data = preferenceService.getPreferences(userid, args.filter),
        jsonStr = data ? jsonUtils.toJSONString(data) : "{}";
    // {"org": {"alfresco": {"share": {"oauth": {"twitter": {"data": "oauth_token=xxx&oauth_token_secret=yyy&oauth_callback_confirmed=true"}}}}}}
    logger.log("user-token.get : Got data = " + jsonStr + " for filter " + args.filter);
    model.jsonStr = jsonStr;
}

main();