require File.dirname(__FILE__) + '/../spec_helper'
  
# Be sure to include AuthenticatedTestHelper in spec/spec_helper.rb instead
# Then, you can remove it from this and the units test.
include ApplicationHelper

describe ApplicationController do
  it 'sets the default section' do
    @current_section.should == 'Home'
  end
end
