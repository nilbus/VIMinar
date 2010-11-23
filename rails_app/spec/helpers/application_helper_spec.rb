require File.dirname(__FILE__) + '/../spec_helper'
include ApplicationHelper

describe ApplicationHelper do
  describe "navigation" do 
    it "detects the current section" do
      @current_section = 'Join'
      current_nav('Join').should == "current_page_item"
    end

    it "creates inactive navigation links" do
      @current_section = 'None'
      nav_for('foo', '/').should == '<li><a href="/">foo</a></li>'
    end

    it "creates active navigation links" do
      @current_section = 'foo'
      nav_for('foo', '/').should == '<li class="current_page_item"><a href="/">foo</a></li>'
    end
  end

end
